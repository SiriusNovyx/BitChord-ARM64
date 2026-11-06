package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.data.settings.TrackAnalysisEligibility
import com.music.bitchord.data.settings.TrackAnalysisStatus
import com.music.bitchord.playback.smart.AnalysisStore
import com.music.bitchord.playback.smart.BeatTracker
import com.music.bitchord.playback.smart.MelSpectrogram
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TrackFeatures
import com.music.bitchord.playback.smart.CurveWindow
import com.music.bitchord.playback.smart.VocalTracker
import com.music.bitchord.playback.smart.VocalSpectrogram
import com.music.bitchord.playback.smart.heuristicVocalOnModelScale
import com.music.bitchord.ui.player.DesktopVisualMetrics
import com.music.bitchord.ui.player.PerformanceStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/** A file on this machine — a download or a local track — rather than anything streamed. */
internal val DesktopStream.isLocalFile: Boolean
    get() = !url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)

/**
 * Produces the evidence Automix mixes on: tempo, beat grid, key, structure and where a track can be
 * entered and left.
 */
internal class DesktopTrackAnalyzer(
    /**
     * How much CPU the listener has allowed background analysis, read fresh on every pass so a
     * change takes effect on the next track rather than the next launch.
     */
    private val performance: () -> AutomixPerformanceMode = { AutomixPerformanceMode.BALANCED },
    /**
     * True while analysis has no use — in a party, where Automix is off. Checked on request, before
     * a queued pass starts, and between and during its decodes, as Android's `TrackAnalyzer` does:
     * a request made a moment before the party started would otherwise run a whole-track decode
     * and a model pass for a transition that cannot happen. A stopped pass records nothing.
     */
    private val stopped: () -> Boolean = { false },
    private val onAnalysed: (String) -> Unit = {},
    private val worker: ExecutorService = analysisWorker(),
    private val sourceOverride: (suspend (Song) -> DesktopStream?)? = null,
    private val currentTimeMs: () -> Long = System::currentTimeMillis,
) {

    /** Analyses kept between runs, so a track measured yesterday is not measured again today. */
    private val store = AnalysisStore { analysisCacheHome() }

    private val results = ConcurrentHashMap<String, TrackAnalysis>()
    private val measuredSources = ConcurrentHashMap<String, DesktopLoopMixRecording>()

    /** Only live measurements have recording provenance; a legacy cache cannot authorize looping. */
    fun measuredSourceFor(trackId: String): DesktopStream? = measuredSources[trackId]?.stream
    fun measuredRecordingFor(trackId: String): DesktopLoopMixRecording? = measuredSources[trackId]
    private val preparation = DesktopPreparationQueue<String>(worker)
    private val active = ConcurrentHashMap.newKeySet<String>()
    private val sourceResolved = ConcurrentHashMap.newKeySet<String>()
    private val noSource = ConcurrentHashMap.newKeySet<String>()

    /** Tracks the analyser could not measure. */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** When each of [failed] last failed, so it is retried on a cooldown rather than every tick. */
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** Said once, not once a tick. */
    private val warnedNoAnalyser = java.util.concurrent.atomic.AtomicBoolean(false)
    private var selectedGeneration: Long? = null

    /** Selection starts the preparation horizon; obsolete passes cannot occupy it. */
    @Synchronized fun selectPair(currentId: String?, nextId: String?, generation: Long? = null) {
        if (generation != null && generation != selectedGeneration) {
            preparation.retain(emptyList())
            selectedGeneration = generation
        }
        preparation.retain(listOfNotNull(currentId, nextId))
    }

    private val beatTracker = lazy {
        BeatTracker(
            // Desktop retains its measured weights independently of Android's default.
            modelPath = { DesktopAnalysisRuntime.modelPath(BeatTracker.RETIRED_MODEL_ASSET) },
            inferenceThreads = { performance().inferenceThreads },
        )
    }
    private val beats by beatTracker
    private val vocalTracker = lazy {
        VocalTracker(
            modelPath = { DesktopAnalysisRuntime.modelPath(VocalTracker.MODEL_ASSET) },
            inferenceThreads = { performance().inferenceThreads },
        )
    }
    private val vocals by vocalTracker

    /** What is known now, never a computation — the planner asks this every tick. */
    fun analysisFor(trackId: String): TrackAnalysis =
        results[trackId] ?: restored(trackId) ?: TrackAnalysis(trackId = trackId)

    /** A stored analysis, promoted into memory on first ask. */
    private fun restored(trackId: String): TrackAnalysis? =
        store.load(trackId)?.also { results[trackId] = it }

    fun isAnalysed(trackId: String): Boolean =
        results.containsKey(trackId) || restored(trackId) != null

    /** How far this track has got, for the player's Automix line. */
    private fun stateFor(trackId: String): TrackAnalysisState = when {
        trackId.isBlank() -> TrackAnalysisState.WAITING
        !DesktopAnalysisRuntime.available -> TrackAnalysisState.FAILED
        // Usable first and a pass in flight second, the order Android settled on.
        results[trackId]?.isUsable == true ->
            if (trackId in active) TrackAnalysisState.REFINING else TrackAnalysisState.ANALYSED
        trackId in active -> TrackAnalysisState.ANALYSING
        // A recorded-but-unusable result is the analyser saying it tried and got nothing, and that
        // it will not try again.
        trackId in failed || results.containsKey(trackId) -> TrackAnalysisState.FAILED
        else -> TrackAnalysisState.WAITING
    }

    fun statusFor(song: Song?, stream: DesktopStream? = null): TrackAnalysisStatus {
        if (song == null || song.videoId.isBlank()) return TrackAnalysisStatus()
        val eligibility = when {
            song.isVideoOrigin -> TrackAnalysisEligibility.VIDEO
            song.isNativeSpotify -> TrackAnalysisEligibility.SOURCE_POLICY
            stream?.isLocalFile != true && DesktopAddonSource.parseTrack(song.videoId) != null -> TrackAnalysisEligibility.SOURCE_POLICY
            !DesktopAnalysisRuntime.available -> TrackAnalysisEligibility.ANALYSER_UNAVAILABLE
            stream?.isLocalFile == true -> TrackAnalysisEligibility.ELIGIBLE
            song.videoId in noSource -> TrackAnalysisEligibility.NO_SOURCE
            DesktopSourceRegistry.hasYouTubeOriginal(song) || song.videoId in sourceResolved || results.containsKey(song.videoId) -> TrackAnalysisEligibility.ELIGIBLE
            song.title.isBlank() -> TrackAnalysisEligibility.NO_SOURCE
            else -> TrackAnalysisEligibility.UNKNOWN
        }
        return TrackAnalysisStatus(eligibility, stateFor(song.videoId))
    }

    /**
     * Asks for [song] to be analysed, if it has not been already.
     *
     * [stream] is only read when it is a file on this machine. Anything streamed is measured on
     * the track's YouTube Opus instead — never on the addon, JioSaavn or lossless copy playback
     * may be using — exactly as Android's analyser reads its own pinned Opus rendition (see
     * `AutomixAnalysisSource`). That keeps an addon from being asked for a track nobody is
     * playing yet, and one recording's measurements from being stored under another's id.
     */
    fun request(song: Song, stream: DesktopStream?, durationSeconds: Double) {
        request(song, stream, durationSeconds, renewRecordingEvidence = false)
    }

    /** Cached grids remain usable while LoopMix establishes this session's exact recording. */
    fun requestLoopEvidence(song: Song, stream: DesktopStream?, durationSeconds: Double) {
        request(song, stream, durationSeconds, renewRecordingEvidence = true)
    }

    fun requestLoopEvidenceForRecording(song: Song, stream: DesktopStream?, durationSeconds: Double,
                                       opened: DesktopLoopMixRecording?) {
        request(song, stream, durationSeconds, renewRecordingEvidence = true, opened = opened)
    }

    private fun request(song: Song, stream: DesktopStream?, durationSeconds: Double,
                        renewRecordingEvidence: Boolean, opened: DesktopLoopMixRecording? = null) {
        val trackId = song.videoId
        if (trackId.isBlank()) return
        if (!durationSeconds.isFinite() || durationSeconds <= 0) return
        if (stopped()) return
        val eligibility = statusFor(song, stream).eligibility
        if (eligibility == TrackAnalysisEligibility.ABSENT || eligibility == TrackAnalysisEligibility.VIDEO ||
            eligibility == TrackAnalysisEligibility.SOURCE_POLICY) return
        if (song.title.isBlank() && eligibility == TrackAnalysisEligibility.NO_SOURCE && trackId !in noSource) return
        if (!DesktopAnalysisRuntime.available) {
            if (warnedNoAnalyser.compareAndSet(false, true)) {
                DesktopTrackLog.log("automix: this build has no analyser, so nothing will be measured")
            }
            return
        }
        val measured = measuredSources[trackId]
        val sameLocalFile = opened?.stream?.isLocalFile != true || opened.matches(measured)
        if ((results.containsKey(trackId) || restored(trackId) != null) &&
            (!renewRecordingEvidence || measured != null && sameLocalFile)) return
        // Asked every tick; a track that just failed is not resolved and decoded again each time.
        failedAt[trackId]?.let { if (currentTimeMs() - it < RETRY_AFTER_MS) return }
        val local = stream?.takeIf { it.isLocalFile }
        noSource.remove(trackId)
        failed.remove(trackId)
        preparation.submit(trackId) { pass ->
            fun cancelled() = pass.cancelled || stopped()
            val measuredFrom = DesktopVisualMetrics.collector.begin()
            try {
                active.add(trackId)
                if (cancelled()) return@submit
                DesktopTrackLog.log(
                    "automix: analysing '${song.title}' (${"%.0f".format(durationSeconds)}s)" +
                        if (local != null) " from the file" else " from YouTube Opus",
                )
                // Android's rule: the lowest rung yields to playback rather than competing for a
                // core, and the thread count stays the speed knob for the other two.
                Thread.currentThread().priority =
                    if (performance().yieldsToPlayback) Thread.MIN_PRIORITY else Thread.NORM_PRIORITY
                var measuredSource: DesktopLoopMixRecording? = null
                val analysis = runCatching {
                    val source = local ?: youTubeOpus(song, pass) ?: return@runCatching null
                    if (cancelled()) return@runCatching null
                    val recording = DesktopLoopMixRecording.capture(source)
                    val result = analyse(trackId, source, durationSeconds, pass)
                    if (recording != null && !recording.isCurrent()) return@runCatching null
                    result.also { if (it != null) measuredSource = recording }
                }
                    .onFailure { if (!cancelled()) DesktopTrackLog.log("analysis of '${song.title}' failed: ${it.message}") }
                    .getOrNull()
                // Cancellation is neither evidence nor a failure/cooldown for a later same-ID pass.
                if (cancelled()) return@submit
                if (analysis == null) {
                    pass.publish {
                        failed += trackId
                        failedAt[trackId] = currentTimeMs()
                    }
                }
                if (analysis != null) {
                    // Ready must not become observable before its restart cache write has settled.
                    // Keep disk IO outside Pass.publish's cancellation lock so controls cannot wait on it.
                    store.save(trackId, analysis)
                    if (!pass.publish {
                        failedAt.remove(trackId)
                        measuredSource?.let { measuredSources[trackId] = it }
                        results[trackId] = analysis
                    }) return@submit
                    DesktopTrackLog.log(
                        "analysed '${song.title}': ${"%.1f".format(analysis.bpm)} bpm" +
                            ", ${analysis.downbeats.size} downbeats" +
                            ", key ${analysis.key.ifBlank { "unknown" }}",
                    )
                    onAnalysed(trackId)
                }
            } catch (error: Exception) {
                if (!cancelled()) DesktopTrackLog.log("analysis of '${song.title}' failed: ${error.message}")
            } finally {
                active.remove(trackId)
                DesktopVisualMetrics.collector.finish(PerformanceStage.TRACK_ANALYSIS, measuredFrom)
            }
        }
    }

    /**
     * [song]'s YouTube Opus stream, bypassing the source race playback runs — or null when there
     * is no YouTube copy of it to measure.
     */
    private fun youTubeOpus(song: Song, pass: DesktopPreparationQueue.Pass): DesktopStream? = runBlocking {
        sourceOverride?.let { resolve ->
            val source = resolve(song)
            if (pass.cancelled) return@runBlocking null
            if (source == null) noSource.add(song.videoId) else sourceResolved.add(song.videoId)
            return@runBlocking source
        }
        val identity = DesktopSourceRegistry.youTubeIdentity(song)
        if (identity == null) {
            noSource.add(song.videoId)
            DesktopTrackLog.log("automix: no YouTube copy of '${song.title}' to measure")
            return@runBlocking null
        }
        noSource.remove(song.videoId)
        sourceResolved.add(song.videoId)
        if (identity.videoId != song.videoId) {
            DesktopTrackLog.log("automix: measuring '${song.title}' on YouTube's ${identity.videoId}")
        }
        DesktopStreamClient.resolve(identity).getOrThrow()
    }

    private fun analyse(
        trackId: String,
        stream: DesktopStream,
        durationSeconds: Double,
        pass: DesktopPreparationQueue.Pass,
    ): TrackAnalysis? {
        // The structural mono pass stays separate from bounded stereo inference windows.
        val (features, effectiveDuration) = structuralFeatures(stream, durationSeconds, pass) ?: return null

        if (stopped() || pass.cancelled) return null
        val span = (VocalTracker.FIXED_FRAMES - 2) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate
        val regions = modelRegions(stream, effectiveDuration, span, pass)
        if (stopped() || pass.cancelled) return null
        val head = DesktopAnalysisWindows.side(regions, 0.0, DesktopAnalysisWindows.headEnd(effectiveDuration, span))
        val tail = DesktopAnalysisWindows.side(regions, DesktopAnalysisWindows.tailStart(effectiveDuration, span), effectiveDuration)
        val leading = tail ?: head
        val vocalWindows = regions.mapNotNull { it.vocal }

        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = effectiveDuration,
            // The grid wins where it has an opinion: a trained model reads meter, which
            // autocorrelation cannot.
            bpm = leading?.bpm ?: features.bpm,
            beatInterval = leading?.let { 60 / it.bpm } ?: features.beatInterval,
            beatConfidence = leading?.confidence ?: features.beatConfidence,
            downbeats = (head?.downbeats.orEmpty() + tail?.downbeats.orEmpty()).distinct().sorted().ifEmpty { features.downbeats },
            firstBeat = head?.beats?.firstOrNull() ?: features.firstBeat,
            phraseBoundaries = features.phraseBoundaries,
            key = features.key,
            keyConfidence = features.keyConfidence,
            audibleStartTime = features.audibleStartTime,
            pickupTime = features.pickupTime,
            introEndTime = features.introEndTime,
            contentEndTime = features.contentEndTime,
            outroStartTime = features.outroStartTime,
            mixInTime = features.mixInTime,
            mixOutTime = features.mixOutTime,
            mixInCandidates = features.mixInCandidates,
            mixOutCandidates = features.mixOutCandidates,
            energyCurve = features.energyCurve,
            lowEnergyCurve = features.lowEnergyCurve,
            vocalActivityMask = DesktopAnalysisWindows.overlay(
                features.energyCurve.map { it.time },
                features.vocalActivityMask.map(::heuristicVocalOnModelScale),
                vocalWindows,
            ),
            vocalProbability = features.vocalProbability,
            headBeats = head,
            tailBeats = tail,
            vocalWindows = vocalWindows,
            levelWindows = regions.mapNotNull { it.level },
        )
    }

    private fun structuralFeatures(
        stream: DesktopStream,
        duration: Double,
        pass: DesktopPreparationQueue.Pass,
    ): Pair<TrackFeatures.Features, Double>? {
        val decoded = decodeMono(stream, TrackFeatures.sampleRate, pass) ?: return null
        val seconds = DesktopAnalysisWindows.measuredDuration(decoded.samples.size, TrackFeatures.sampleRate, duration, decoded.containerDuration)
            ?: return null
        return TrackFeatures.analyze(decoded.samples, seconds)?.let { it to seconds }
    }

    private fun modelRegions(
        stream: DesktopStream,
        duration: Double,
        span: Double,
        pass: DesktopPreparationQueue.Pass,
    ): List<DesktopAnalysisWindows.Evidence> {
        val decoder = DesktopAudioDecoder()
        val unbind = pass.onCancel(decoder::cancelPendingRead)
        fun cancelled() = stopped() || pass.cancelled
        return try {
            if (cancelled()) return emptyList()
            val rate = VocalSpectrogram.sampleRate.toInt()
            if (decoder.open(
                url = stream.url, headers = stream.headers,
                requested = DesktopPcmFormat(rate, channels = 2, bytesPerSample = 4, isFloat = true),
                windowed = stream.windowedReads, transport = stream.transport,
                preserveSourceSampleRate = false,
            ).isFailure) return emptyList()
            val reader = DesktopAnalysisWindowReader(rate, ::cancelled) {
                decoder.readSamples()?.copyOf(decoder.sampleCount)
            }
            val regions = ArrayList<DesktopAnalysisWindows.Evidence>()
            for ((from, until) in DesktopAnalysisWindows.plan(duration, span)) {
                if (cancelled()) return emptyList()
                val pcm = reader.read(from, until) ?: continue
                if (pcm.seconds < 1) continue
                val mono = FloatArray(pcm.left.size) { (pcm.left[it] + pcm.right[it]) * .5f }
                val modelInput = MelSpectrogram.resample(mono, rate.toDouble(), MelSpectrogram.sampleRate)
                if (cancelled()) return emptyList()
                val grid = modelInput?.let { beats.track(it, offsetSeconds = pcm.start) }
                if (cancelled()) return emptyList()
                val values = vocals.track(pcm.left, pcm.right, rate.toDouble())
                if (cancelled()) return emptyList()
                // STFT padding is not audio coverage. Never label its padded frames as measured.
                val vocal = values?.let {
                    val count = minOf(it.size, kotlin.math.floor(pcm.seconds * VocalSpectrogram.frameRate).toInt())
                    if (count > 0) CurveWindow(pcm.start, VocalSpectrogram.frameRate, it.copyOf(count)) else null
                }
                regions += DesktopAnalysisWindows.Evidence(pcm.start, pcm.seconds, grid, vocal, DesktopAnalysisWindows.levels(pcm))
            }
            regions
        } finally {
            unbind()
            decoder.close()
        }
    }

    /** The whole track as mono float at [rate]. */
    private fun decode(stream: DesktopStream, rate: Double, pass: DesktopPreparationQueue.Pass): FloatArray? =
        decodeMono(stream, rate, pass)?.samples

    private class DecodedMono(val samples: FloatArray, val containerDuration: Double?)

    private fun decodeMono(stream: DesktopStream, rate: Double, pass: DesktopPreparationQueue.Pass): DecodedMono? {
        val decoder = DesktopAudioDecoder()
        val unbind = pass.onCancel(decoder::cancelPendingRead)
        return try {
            if (stopped() || pass.cancelled) return null
            val opened = decoder.open(
                url = stream.url,
                headers = stream.headers,
                requested = DesktopPcmFormat(rate.toInt(), channels = 1, bytesPerSample = 4, isFloat = true),
                windowed = stream.windowedReads,
                transport = stream.transport,
                preserveSourceSampleRate = false,
            )
            if (opened.isFailure) return null
            val out = ArrayList<FloatArray>()
            var total = 0
            while (true) {
                if (stopped() || pass.cancelled) return null
                val block = decoder.readSamples() ?: break
                val count = decoder.sampleCount
                if (count <= 0) continue
                val retained = minOf(count, MAX_SAMPLES - total)
                out += block.copyOf(retained)
                total += retained
                // A capped decode is not a measured end of the song.
                if (total >= MAX_SAMPLES) return null
            }
            if (total == 0) return null
            val samples = FloatArray(total)
            var at = 0
            for (block in out) {
                block.copyInto(samples, at)
                at += block.size
            }
            DecodedMono(samples, decoder.durationUs?.div(1_000_000.0))
        } finally {
            unbind()
            decoder.close()
        }
    }

    val isTerminated: Boolean get() = preparation.isTerminated

    fun awaitTermination(timeoutNanos: Long): Boolean = preparation.awaitTermination(timeoutNanos)

    fun release() {
        preparation.close {
            if (beatTracker.isInitialized()) beatTracker.value.release()
            if (vocalTracker.isInitialized()) vocalTracker.value.release()
        }
        results.clear()
        measuredSources.clear()
        active.clear()
        sourceResolved.clear()
        noSource.clear()
        failed.clear()
        failedAt.clear()
    }

    internal companion object {
        // Preserve earlier caches; this contract requires bounded regions, the corrected PCM
        // clock, and desktop's retained beat weights before loop evidence can be restored.
        internal fun analysisCacheHome(): java.io.File =
            java.io.File(DesktopAnalysisRuntime.analysisHome(), "native-regions-v19-retired-beats-v1")

        private fun analysisWorker(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(null, runnable, "BitChord-Analysis", ANALYSIS_STACK_BYTES).apply { isDaemon = true }
        }

        /** How long a track that failed to be measured waits before it is tried again. */
        const val RETRY_AFTER_MS = 60_000L

        /**
         * A ceiling of about twenty minutes at the analyser's rate, so a mis-tagged eight-hour
         * upload cannot eat the heap.
         */
        const val MAX_SAMPLES = 20 * 60 * 11_025

        /** Stack for the analysis thread. */
        const val ANALYSIS_STACK_BYTES = 64L * 1024 * 1024
    }
}

/** The queue facts the shared planner needs, from a [Song]. */
internal fun Song.transitionInfo(durationMs: Long) = com.music.bitchord.playback.smart.TransitionTrackInfo(
    id = videoId,
    durationMs = durationMs,
    title = title,
    artist = artist,
    album = albumName.orEmpty(),
    albumId = albumId.orEmpty(),
)

/** "3:27" as seconds, or zero when there is nothing to read. */
internal fun String?.durationSeconds(): Double {
    val parts = this?.split(':')?.mapNotNull { it.trim().toIntOrNull() } ?: return 0.0
    return when (parts.size) {
        2 -> (parts[0] * 60 + parts[1]).toDouble()
        3 -> (parts[0] * 3_600 + parts[1] * 60 + parts[2]).toDouble()
        else -> 0.0
    }
}
