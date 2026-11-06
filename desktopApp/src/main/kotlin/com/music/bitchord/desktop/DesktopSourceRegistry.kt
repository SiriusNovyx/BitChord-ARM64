package com.music.bitchord.desktop

import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/** What playback got to start with, and what is still being looked for. */
internal class DesktopLiveResolution(
    val stream: DesktopStream,
    /**
     * The substitution search that had not finished when sound started, or null when nothing is
     * outstanding.
     */
    val pendingSubstitute: Deferred<DesktopStream?>? = null,
)

internal data class DesktopSourceDescriptor(
    val id: String,
    val name: String,
    val detail: String,
    val kind: DesktopSourceKind,
    val configured: Boolean,
    val enabled: Boolean,
    val supportsLossless: Boolean,
    /** Enabled, but the ceiling in force will not let it answer a stream. */
    val skippedByQuality: Boolean = false,
)

/** Desktop counterpart of Android's SourceRegistry. */
internal object DesktopSourceRegistry {
    internal fun recordFailure(config: DesktopSourceConfig, failure: Throwable) = availability.recordFailure(config, failure)
    private interface SourceAdapter {
        val config: DesktopSourceConfig
        val descriptor: DesktopSourceDescriptor
        suspend fun resolve(song: Song, quality: String?): Result<DesktopStream?>
        /** [resolve] for a row this source owns, played as itself rather than as a match. */
        suspend fun resolveOwned(song: Song, quality: String?): Result<DesktopStream?> = resolve(song, quality)
        /** Every copy this source holds of [song], most confident first. */
        suspend fun matches(song: Song): List<Song> = emptyList()

        suspend fun match(song: Song): Song? = matches(song).firstOrNull()
        fun owns(song: Song): Boolean = false
    }

    private class AddonAdapter(
        override val config: DesktopSourceConfig,
    ) : SourceAdapter {
        override val descriptor: DesktopSourceDescriptor
            get() = config.descriptor()

        override fun owns(song: Song): Boolean =
            DesktopAddonSource.parseTrack(song.videoId)?.sourceId == config.id

        override suspend fun resolve(song: Song, quality: String?): Result<DesktopStream?> =
            DesktopAddonSource.stream(config, song, quality)

        override suspend fun resolveOwned(song: Song, quality: String?): Result<DesktopStream?> =
            DesktopAddonSource.stream(config, song, quality, picked = true)

        override suspend fun matches(song: Song): List<Song> {
            if (song.title.isBlank() || song.isVideo) return emptyList()
            for (query in DesktopTrackMatcher.queries(song)) {
                val candidates = DesktopAddonSource.search(config, query, 25).getOrThrow()
                    .mapNotNull { (it as? SearchResult.Track)?.song }
                DesktopTrackMatcher.ranked(candidates, song).ifEmpty { null }?.let { return it }
            }
            return emptyList()
        }
    }

    private class ModuleAdapter(
        override val config: DesktopSourceConfig,
    ) : SourceAdapter {
        override val descriptor: DesktopSourceDescriptor
            get() = config.descriptor()

        override fun owns(song: Song): Boolean =
            DesktopModuleSource.parseTrack(song.videoId)?.let { ref ->
                ref.sourceId == null || ref.sourceId == config.id
            } == true

        override suspend fun resolve(song: Song, quality: String?): Result<DesktopStream?> =
            DesktopModuleSource.stream(config, song, quality)

        override suspend fun matches(song: Song): List<Song> = DesktopModuleSource.matches(config, song)
    }

    private class JioAdapter(
        override val config: DesktopSourceConfig,
    ) : SourceAdapter {
        override val descriptor: DesktopSourceDescriptor
            get() = config.descriptor()

        override fun owns(song: Song): Boolean = song.videoId.startsWith("jiosaavn:")

        override suspend fun resolve(song: Song, quality: String?): Result<DesktopStream?> =
            DesktopJioSaavn.stream(song.videoId).map { it?.copy(sourceId = config.id) }

        override suspend fun matches(song: Song): List<Song> {
            if (song.title.isBlank() || song.isVideo) return emptyList()
            for (query in DesktopTrackMatcher.queries(song)) {
                val candidates = DesktopJioSaavn.search(query).getOrThrow()
                    .mapNotNull { (it as? SearchResult.Track)?.song }
                DesktopTrackMatcher.ranked(candidates, song).ifEmpty { null }?.let { return it }
            }
            return emptyList()
        }
    }

    private class YouTubeAdapter(
        override val config: DesktopSourceConfig,
    ) : SourceAdapter {
        override val descriptor: DesktopSourceDescriptor
            get() = config.descriptor()

        override suspend fun resolve(song: Song, quality: String?): Result<DesktopStream?> =
            DesktopStreamClient.resolve(song, youtubeCeiling(quality)).map { it.copy(sourceId = config.id) }

        /** Allows an unavailable module/Jio stream to fall back by identity. */
        override suspend fun matches(song: Song): List<Song> = youTubeMatches(song)
    }

    /** YouTube Music's copies of [song], most confident first, found by title and artist. */
    private suspend fun youTubeMatches(song: Song, failOnError: Boolean = false): List<Song> {
        if (song.title.isBlank() || song.isVideo) return emptyList()
        val queries = DesktopTrackMatcher.queries(song)
        if (failOnError) return com.music.bitchord.playback.smart.findAnalysisSource(queries) { query ->
            val candidates = DesktopSearchClient.search(query, SearchFilter.SONGS).getOrThrow()
                .mapNotNull { (it as? SearchResult.Track)?.song }
            DesktopTrackMatcher.ranked(candidates, song).ifEmpty { null }
        }.orEmpty()
        for (query in queries) {
            val search = DesktopSearchClient.search(query, SearchFilter.SONGS)
            val candidates = search.getOrDefault(emptyList())
                .mapNotNull { (it as? SearchResult.Track)?.song }
            DesktopTrackMatcher.ranked(candidates, song).ifEmpty { null }?.let { return it }
        }
        return emptyList()
    }

    /**
     * The YouTube upload Automix measures [song] on: the row itself when it is one, else the best
     * YouTube Music match for an addon, module or JioSaavn row. Android's
     * `TrackAnalyzer.analysisUriFor` does the same.
     */
    suspend fun youTubeIdentity(song: Song): Song? = when {
        hasYouTubeOriginal(song) -> song
        // A row picked from the Addons search is that addon's or nothing — no YouTube search and
        // Opus fetch behind it just to measure it. Left unmeasured, as in a party.
        DesktopAddonSource.parseTrack(song.videoId) != null -> null
        else -> youTubeMatches(song, failOnError = true).firstOrNull()
    }

    /**
     * Whether resolving [song] could ask an addon for it — an addon's own row, or a YouTube row
     * with an addon ranked above YouTube to substitute it.
     *
     * Such a track is not read ahead: an addon is only asked for a stream once the track is about
     * to play, as on Android, whose read-ahead only warms sources that are
     * `worthPrefetching`.
     */
    fun mayServeFromAddon(song: Song): Boolean {
        val addons = playbackAdapters(null).filterIsInstance<AddonAdapter>()
        if (addons.isEmpty()) return false
        if (DesktopAddonSource.parseTrack(song.videoId) != null) return true
        return hasYouTubeOriginal(song) && !song.isVideo && !DesktopOriginalVersion.isPinned(song.videoId)
    }

    private fun DesktopSourceConfig.descriptor() = DesktopSourceDescriptor(
        id = id,
        name = displayName,
        detail = if (kind.needsServer && baseUrl.isBlank()) "Setup required" else kind.detail,
        kind = kind,
        configured = isComplete,
        enabled = enabled,
        supportsLossless = kind.supportsLossless,
        skippedByQuality = enabled && !DesktopPersistence().audioQuality().permits(kind),
    )

    private fun configs(): List<DesktopSourceConfig> = DesktopPersistence().sourceConfigs()

    /**
     * The sources a *stream* may come from: enabled, complete, and permitted by the ceiling in
     * force.
     */
    private fun playbackAdapters(quality: String?, forDownload: Boolean = false, configured: List<DesktopSourceConfig> = configs()): List<SourceAdapter> {
        val ceiling = ceiling(quality)
        return adapters(forDownload, configured).filter { ceiling.permits(it.config.kind) }
    }

    /**
     * For a download, minus any addon whose manifest says `allowDownloads: 0`, so the next source
     * in line serves the file.
     */
    private fun adapters(forDownload: Boolean = false, configured: List<DesktopSourceConfig> = configs()): List<SourceAdapter> =
        configured
            .inSourceOrder()
            .filter { availability.canAttempt(it) }
            .filterNot { forDownload && it.kind == DesktopSourceKind.ADDON && !DesktopAddonSource.allowsDownloads(it) }
            .mapNotNull { config ->
                when (config.kind) {
                    DesktopSourceKind.ADDON -> AddonAdapter(config)
                    DesktopSourceKind.CUSTOM_MODULE,
                    DesktopSourceKind.MODULE,
                    -> ModuleAdapter(config)
                    DesktopSourceKind.JIOSAAVN -> JioAdapter(config)
                    DesktopSourceKind.YOUTUBE -> YouTubeAdapter(config)
                    DesktopSourceKind.SPOTIFY -> null
                }
            }

    fun descriptors(): List<DesktopSourceDescriptor> = configs()
        .inSourceOrder()
        .map { it.descriptor() }

    fun isEnabled(sourceId: String): Boolean = configs().firstOrNull { it.id == sourceId }?.let(availability::canAttempt) == true

    private val availability = DesktopSourceAvailability()

    fun recordAvailability(config: DesktopSourceConfig, available: Boolean, rejected: Boolean = false) {
        if (available) availability.recovered(config) else availability.unavailable(config, rejected)
    }

    /** Searching is YouTube Music's job, and only YouTube Music's. */
    suspend fun search(query: String, filter: SearchFilter): Result<List<SearchResult>> =
        DesktopSearchClient.search(query, filter)

    suspend fun resolve(
        song: Song,
        quality: String?,
        excludedSourceId: String? = null,
        /** A download: addons that said `allowDownloads: 0` are left out, see [adapters]. */
        forDownload: Boolean = false,
    ): Result<DesktopStream> = resolveConfigured(song, quality, configs(), excludedSourceId, forDownload)

    internal suspend fun resolveConfigured(
        song: Song,
        quality: String?,
        configured: List<DesktopSourceConfig>,
        excludedSourceId: String? = null,
        forDownload: Boolean = false,
    ): Result<DesktopStream> = runCatching {
        val available = playbackAdapters(quality, forDownload, configured)
        val moduleReference = DesktopModuleSource.parseTrack(song.videoId)
        val addonReference = DesktopAddonSource.parseTrack(song.videoId)
        val selected = when {
            addonReference != null -> configured.firstOrNull { it.id == addonReference.sourceId }
            moduleReference?.sourceId != null -> configured.firstOrNull { it.id == moduleReference.sourceId }
            moduleReference != null -> available.firstOrNull { it.config.kind == DesktopSourceKind.CUSTOM_MODULE || it.config.kind == DesktopSourceKind.MODULE }?.config
                ?: configured.firstOrNull { it.kind == DesktopSourceKind.CUSTOM_MODULE || it.kind == DesktopSourceKind.MODULE }
            song.videoId.startsWith("jiosaavn:") -> available.firstOrNull { it.config.kind == DesktopSourceKind.JIOSAAVN }?.config
                ?: configured.firstOrNull { it.kind == DesktopSourceKind.JIOSAAVN }
            else -> null
        }
        val owned = when {
            addonReference != null -> available.firstOrNull { it.config.id == addonReference.sourceId }
            moduleReference?.sourceId != null -> available.firstOrNull { it.config.id == moduleReference.sourceId }
            moduleReference != null -> available.firstOrNull {
                it.config.kind == DesktopSourceKind.CUSTOM_MODULE || it.config.kind == DesktopSourceKind.MODULE
            }
            song.videoId.startsWith("jiosaavn:") -> available.firstOrNull { it.config.kind == DesktopSourceKind.JIOSAAVN }
            else -> null
        }

        // A row from an addon that is out of the walk only for policy — a download from one whose
        // manifest says `allowDownloads: 0` — is looked up elsewhere, like any other track, rather
        // than refused the way a disabled source's row is.
        val heldBack = addonReference != null && selected != null && selected.enabled && selected.isComplete &&
            forDownload && !DesktopAddonSource.allowsDownloads(selected)
        if (!heldBack && (addonReference != null || moduleReference != null || song.videoId.startsWith("jiosaavn:"))) {
            if (selected == null || !selected.enabled || !selected.isComplete || !ceiling(quality).permits(selected.kind)) {
                error("The source for this track is disabled, unavailable at this quality, or no longer configured")
            }
            // This path has nothing to race.
            DesktopTrackLog.log(
                "resolving '${song.title}' — this row belongs to ${selected.displayName}" +
                    ", asking at the ${(quality ?: DesktopPersistence().audioQuality().name).lowercase()} rung",
            )
            if (owned != null && owned.config.id != excludedSourceId) {
                walkDesktopSources(listOf(owned.config), availability) {
                    owned.resolveOwned(song, quality).getOrThrow()?.takeIf { playableHere(owned, it) }
                }
                    ?.let {
                        DesktopTrackLog.log(
                            "  ${owned.descriptor.name} served it at " +
                                it.format.summary.ifBlank { "an unstated format" },
                        )
                        return@runCatching it
                    }
                DesktopTrackLog.log("  ${owned.descriptor.name} had no usable stream for it")
            }
            // Android walks on to other active sources when the pinned source cannot produce a
            // stream.
            // Never YouTube under an addon's own row: its Opus is not the copy that was picked.
            val fallbackIds = ownedSourceFallbackConfigs(available.map { it.config }, selected.id, addonReference != null)
                .mapTo(mutableSetOf()) { it.id }
            val fallbacks = available.filter { it.config.id in fallbackIds && it.config.id != excludedSourceId }
            val fallbackById = fallbacks.associateBy { it.config.id }
            walkDesktopSources(fallbacks.map { it.config }, availability) { config ->
                val fallback = fallbackById.getValue(config.id)
                openBest(fallback, song, quality)
            }?.let { return@runCatching it }
            error("${selected.displayName} did not return an audio stream")
        }

        val byId = available.associateBy { it.config.id }
        walkDesktopSources(available.map { it.config }, availability) { config ->
            if (config.id == excludedSourceId) return@walkDesktopSources null
            val source = byId.getValue(config.id)
            if (DesktopOriginalVersion.isPinned(song.videoId) && config.kind != DesktopSourceKind.YOUTUBE) {
                return@walkDesktopSources null
            }
            if (config.kind == DesktopSourceKind.YOUTUBE) {
                val target = if (heldBack) source.match(song) ?: return@walkDesktopSources null else song
                source.resolve(target, quality).getOrThrow()?.takeIf { playableHere(source, it) }
            } else openBest(source, song, quality)
        } ?: error("No enabled music source could play this track")
    }

    /** Foreground playback uses the persisted walk; lower sources wait for higher ones. */
    suspend fun resolveLive(
        song: Song,
        quality: String?,
        excludedSourceId: String? = null,
    ): Result<DesktopLiveResolution> = resolve(song, quality, excludedSourceId).map { DesktopLiveResolution(it) }

    private suspend fun firstSubstitute(
        sources: List<SourceAdapter>,
        song: Song,
        quality: String?,
    ): DesktopStream? {
        val byId = sources.associateBy { it.config.id }
        return walkDesktopSources(sources.map { it.config }, availability) { openBest(byId.getValue(it.id), song, quality) }
    }

    /** Whether a copy found after the fact is worth interrupting playback for. */
    internal fun worthSwapping(candidate: DesktopStreamFormat, playing: DesktopStreamFormat?): Boolean {
        if (candidate.isLossless) return true
        val gain = (candidate.kbps ?: return false) - (playing?.kbps ?: return false)
        return gain >= UPGRADE_MIN_GAIN_KBPS
    }

    /**
     * A second look, asked from scratch once the first one has settled for nothing better.
     *
     * Sources above the playing source in saved order are considered for an improvement.
     */
    suspend fun upgradeFor(
        song: Song,
        playing: DesktopStream?,
        quality: String? = null,
    ): DesktopStream? {
        // This is an explicit second look. Empty catalogue answers and failed/expired stream URLs
        // from the first pass must not make the retry a cache hit.
        DesktopAddonSource.clearCompletedTrackCalls()
        val ordered = playbackAdapters(quality)
        val playingIndex = ordered.indexOfFirst { it.config.id == playing?.sourceId }
        val better = if (playingIndex < 0) emptyList() else ordered.take(playingIndex)
        if (better.isEmpty()) return null
        DesktopTrackLog.log(
            "asking ${better.joinToString { it.descriptor.name }} again for a better copy of " +
                "'${song.title}'",
        )
        return withTimeoutOrNull(UPGRADE_TIMEOUT_MS) { firstSubstitute(better, song, quality) }
    }

    /** Whether a stream a source handed back can actually be played here. */
    /**
     * The best copy [source] can actually serve for [song], or null when it has none.
     *
     * More than one row is opened, because a catalogue that holds a track can still fail on the
     * particular row matched first — and rows advertising the tier being asked for go first.
     */
    private suspend fun openBest(
        source: SourceAdapter,
        song: Song,
        quality: String?,
    ): DesktopStream? {
        val matches = source.matches(song)
        if (matches.isEmpty()) {
            // A leg cancelled by a sibling winning the race has not missed anything.
            if (currentCoroutineContext().isActive) {
                DesktopTrackLog.log("${source.descriptor.name} has no copy of '${song.title}'")
            }
            return null
        }
        val wantsLossless = ceiling(quality) == DesktopAudioQuality.LOSSLESS
        var settleFor: DesktopStream? = null
        for (match in preferred(matches, song, wantsLossless).take(STREAM_ATTEMPTS)) {
            if (!availability.canAttempt(source.config)) return settleFor
            val outcome = source.resolve(match, quality)
            outcome.exceptionOrNull()?.let { failure ->
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                if (failure.isProviderWideSourceFailure()) {
                    recordFailure(source.config, failure)
                    return settleFor
                }
            }
            val stream = outcome.getOrNull() ?: continue
            if (!playableHere(source, stream)) continue
            val served = stream.format
            if (!wantsLossless || served.isLossless || served.isDolbyAtmos) {
                DesktopTrackLog.log(
                    "${source.descriptor.name} matched '${match.title}' by '${match.artist}'" +
                        " → ${served.summary.ifBlank { "an unstated format" }}",
                )
                return stream
            }
            DesktopTrackLog.log(
                "${source.descriptor.name} offered ${served.summary.ifBlank { "an unstated format" }}" +
                    " for '${match.title}'; looking further",
            )
            // The floor is the best of what was refused, not the first of it.
            settleFor = betterOf(settleFor, stream)
        }
        if (settleFor == null && currentCoroutineContext().isActive) {
            DesktopTrackLog.log("${source.descriptor.name} matched '${song.title}' but returned no stream")
        }
        return settleFor
    }

    /** The matching rows in the order they are worth opening. */
    internal fun preferred(matches: List<Song>, target: Song, wantsLossless: Boolean): List<Song> {
        val sameLength = matches.filter { DesktopTrackMatcher.withinSeconds(it, target, SAME_RECORDING_SEC) }
        val eligible = sameLength.ifEmpty { matches }
        if (!wantsLossless) return eligible
        // Stable, so the confidence order the matcher produced survives inside each tier.
        return eligible.sortedByDescending { it.sourceQuality == DesktopModuleSource.LOSSLESS }
    }

    /** Which of two renditions of the same recording is the better one. */
    private fun betterOf(current: DesktopStream?, candidate: DesktopStream): DesktopStream {
        val held = current ?: return candidate
        val a = candidate.format
        val b = held.format
        if (a.isLossless != b.isLossless) return if (a.isLossless) candidate else held
        if (a.isDolbyAtmos != b.isDolbyAtmos) return if (a.isDolbyAtmos) candidate else held
        return if ((a.kbps ?: 0) > (b.kbps ?: 0)) candidate else held
    }

    private fun playableHere(source: SourceAdapter, stream: DesktopStream): Boolean {
        if (stream.isDolbyAtmos && !DesktopCodecs.supportsDolbyAtmos) {
            DesktopTrackLog.log(
                "${source.descriptor.name} offered a Dolby Atmos rendition, " +
                    "which this build has no E-AC-3 decoder for",
            )
            return false
        }
        if (stream.isDolbyAtmos && !DesktopAddonSettings.dolbyAtmosEnabled) {
            DesktopTrackLog.log(
                "${source.descriptor.name} offered a Dolby Atmos rendition, which is switched off",
            )
            return false
        }
        return true
    }

    /** Whether this row has a YouTube upload behind it to go back to. */
    fun hasYouTubeOriginal(song: Song): Boolean =
        DesktopAddonSource.parseTrack(song.videoId) == null &&
            DesktopModuleSource.parseTrack(song.videoId) == null &&
            !song.videoId.startsWith("jiosaavn:") &&
            song.videoId.isNotBlank()

    /** The configured source a stream came from, for a log line. */
    internal fun sourceNameFor(stream: DesktopStream): String =
        configs().firstOrNull { it.id == stream.sourceId }?.displayName ?: "a configured source"


    /**
     * The patient second look's budget. Longer than the race's, because nobody is waiting on the
     * first note by then and a cold addon routinely needs more than one call to settle.
     */
    private const val UPGRADE_TIMEOUT_MS = 90_000L

    /** How many matched rows are opened before a source is written off. */
    private const val STREAM_ATTEMPTS = 3

    /** The runtime agreement that says two rows are the same recording. */
    private const val SAME_RECORDING_SEC = 2

    /** The smallest bitrate gain worth a break in the audio for. */
    private const val UPGRADE_MIN_GAIN_KBPS = 96

    /** The ceiling a call is made under: an explicit override, else the stored rung. */
    internal fun ceiling(qualityOverride: String?): DesktopAudioQuality =
        qualityOverride?.let { override ->
            DesktopAudioQuality.entries.firstOrNull { it.name.equals(override, ignoreCase = true) }
        } ?: DesktopPersistence().audioQuality()

    private fun youtubeCeiling(qualityOverride: String?): Int = ceiling(qualityOverride).maxKbps
}
