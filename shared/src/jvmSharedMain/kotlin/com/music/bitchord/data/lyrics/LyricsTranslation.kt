package com.music.bitchord.data.lyrics

import com.music.bitchord.data.Http
import com.music.bitchord.data.DebugLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Lightweight, on-demand lyric translation.
 *
 * Translation models are deliberately not installed on the device. A model for
 * every app language would cost tens of megabytes each; translated lyric text
 * is normally only a few kilobytes. Requests are batched and the compact result
 * is kept in a bounded cache under [Context.getCacheDir], so Android may reclaim
 * it and the feature can never grow without limit.
 */
object LyricsTranslation {
    sealed interface Result {
        data class Translated(
            val lines: List<LyricLine>,
            val sourceLanguage: String,
            val fromCache: Boolean,
        ) : Result

        data class SameLanguage(val language: String) : Result
        data class RateLimited(val retryAfterMillis: Long, val lines: List<LyricLine>? = null) : Result
        data object Unavailable : Result
    }

    sealed interface RomanizationResult {
        data class Romanized(
            val lines: List<LyricLine>,
            val sourceLanguage: String,
            val fromCache: Boolean,
        ) : RomanizationResult

        data object AlreadyRomanized : RomanizationResult
        data class RateLimited(val retryAfterMillis: Long) : RomanizationResult
        data object Unavailable : RomanizationResult
    }

    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val CACHE_VERSION = 3
    private const val TRANSLATION_VERSION = 4
    private const val CACHE_DIRECTORY = "lyrics_translation_v3"
    private const val MAX_CACHE_BYTES = 2L * 1024L * 1024L
    private const val MAX_BATCH_CHARS = 3_500
    private const val MAX_PARALLEL_REQUESTS = 2
    // Input Tools changes ASCII digits to the destination script, so match the
    // private-use wrapper rather than assuming the counter remains ASCII.
    private val markerRegex = Regex("\\uE000[^\\uE001]*\\uE001")
    private val json = Json { ignoreUnknownKeys = true }
    private val diskMutex = Mutex()
    private val memory = object {
        private val entries = object : LinkedHashMap<String, CachedTranslation>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, CachedTranslation>) = size > 12
        }

        @Synchronized fun get(key: String): CachedTranslation? = entries[key]

        @Synchronized fun put(key: String, value: CachedTranslation) {
            entries[key] = value
        }
    }

    /**
     * Where translated lyrics are kept between runs — the phone's cache
     * directory, the desktop's media cache. Null keeps them in memory only.
     */
    @Volatile
    var cacheDir: File? = null

    @Volatile
    internal var transport: (suspend (Request) -> String)? = null

    @Volatile
    internal var providerRequests = LyricsTranslationRateLimit()

    private suspend fun executeRequest(request: Request): String {
        val form = request.body as FormBody
        val identity = (0 until form.size).joinToString("\u0000") { form.name(it) + "=" + form.value(it) }
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        return providerRequests.execute(key) { transport?.invoke(request) ?: client.newCall(request).awaitBody() }
    }

    private fun diagnostic(key: String, target: String, details: String) {
        val safeTarget = target.takeIf { it.matches(Regex("[a-zA-Z]{2,3}([-_][a-zA-Z0-9]{2,8})*")) } ?: "invalid"
        DebugLog.d("LyricsTranslation", "id=${key.take(12)} target=$safeTarget $details")
    }

    private val client by lazy {
        Http.client.newBuilder()
            .callTimeout(12, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @Serializable
    private data class CachedTranslation(
        val version: Int = CACHE_VERSION,
        val sourceLanguage: String,
        val targetLanguage: String,
        val texts: List<String>,
        val allSameLanguage: Boolean = false,
    )

    private data class ResolvedText(val entry: CachedTranslation, val fromCache: Boolean)
    private val requests = LyricsRequestCoalescer<ResolvedText?>()

    private suspend fun cachedText(key: String, slots: Int, version: Int = CACHE_VERSION): CachedTranslation? {
        val inMemory = memory.get(key)?.takeIf { it.version == version && it.texts.size == slots }
        if (inMemory != null) {
            diagnostic(key, inMemory.targetLanguage, "cache=memory")
            return inMemory
        }
        return readCache(key, version)?.takeIf { it.texts.size == slots }?.also {
            memory.put(key, it)
            diagnostic(key, it.targetLanguage, "cache=disk")
        }
    }

    private data class TextSlot(
        val lineIndex: Int,
        val background: Boolean,
        val text: String,
        val sectionHeader: Boolean,
    )

    private data class Batch(
        val slots: List<TextSlot>,
        val payload: String,
    )

    private data class BatchAnswer(
        val translations: List<String>,
        val sourceLanguage: String,
        val sourceWeight: Int,
        val confidence: Double? = null,
        val pronunciations: List<String> = emptyList(),
    )

    private data class Segment(val slot: Int, val start: Int, val end: Int, val text: String)
    private data class SegmentAnswer(val text: String, val source: String?, val same: Boolean, val complete: Boolean)

    suspend fun translate(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): Result {
        // Sent as given rather than reduced to a base language: zh-CN and
        // zh-TW are the same language in two scripts, and canonicalising either
        // to "zh" hands back Simplified whichever one was asked for. The
        // narrowing is still done, but only where it belongs — in
        // [sameLanguage], which is asking a different question.
        val target = targetLanguageTag.trim().let { if (canonicalLanguage(it) == "th") "th" else it }
        if (target.isBlank() || lines.isEmpty()) return Result.Unavailable

        val slots = flatten(lines)
        if (slots.isEmpty()) return Result.Unavailable
        val cacheKey = cacheKey(trackId, "translate:v$TRANSLATION_VERSION:$target", slots, translation = true)
        val cached = cachedText(cacheKey, slots.size, TRANSLATION_VERSION)
        if (cached != null) {
            diagnostic(cacheKey, target, "cache=hit outcome=${if (cached.allSameLanguage) "same_language" else "translated"} slots=${slots.size}")
            return if (cached.allSameLanguage) {
                Result.SameLanguage(cached.sourceLanguage)
            } else {
                Result.Translated(
                    lines = rebuild(lines, slots, cached.texts),
                    sourceLanguage = cached.sourceLanguage,
                    fromCache = true,
                )
            }
        }

        diagnostic(cacheKey, target, "cache=miss slots=${slots.size}")
        val answer = try {
            requests.run("translate:$cacheKey") {
                cachedText(cacheKey, slots.size, TRANSLATION_VERSION)?.let { ResolvedText(it, true) }
                    ?: fetchTranslation(cacheKey, target, slots)?.let { ResolvedText(it, false) }
            }
        } catch (limited: LyricsTranslationCooldown) {
            diagnostic(cacheKey, target, "outcome=rate_limited retry_after_ms=${limited.retryAfterMillis}")
            return Result.RateLimited(limited.retryAfterMillis)
        }
        val cooldown = providerRequests.cooldown()?.takeIf { it.rateLimited }
        if (answer == null) {
            diagnostic(cacheKey, target, "outcome=${if (cooldown != null) "rate_limited" else "unavailable"}")
            return cooldown?.let { Result.RateLimited(it.retryAfterMillis) } ?: Result.Unavailable
        }
        val entry = answer.entry
        if (!answer.fromCache && cooldown != null) {
            diagnostic(cacheKey, target, "outcome=rate_limited partial=true retry_after_ms=${cooldown.retryAfterMillis}")
            return Result.RateLimited(cooldown.retryAfterMillis, rebuild(lines, slots, entry.texts))
        }
        diagnostic(cacheKey, target, "outcome=${if (entry.allSameLanguage) "same_language" else "translated"} source=${entry.sourceLanguage}")
        return if (entry.allSameLanguage) Result.SameLanguage(entry.sourceLanguage)
        else Result.Translated(rebuild(lines, slots, entry.texts), entry.sourceLanguage, answer.fromCache)
    }

    private suspend fun fetchTranslation(cacheKey: String, target: String, slots: List<TextSlot>): CachedTranslation? {
        val segments = slots.flatMapIndexed { index, slot -> segments(index, slot.text) }
        if (segments.isEmpty()) return null
        val texts = segments.map { it.text }.distinct()
        // The two boundary anchors retain per-line source evidence for short/contextual lyrics.
        // Remaining compatible scripts share marker-delimited auto-detection batches; a batch's
        // dominant source must never be attributed to each of its multilingual members.
        val anchors = listOfNotNull(texts.firstOrNull(), texts.lastOrNull()).distinct()
        val first = parallel(anchors) { text -> requestSegment(text, target) }.toMap().toMutableMap()
        val remaining = texts.filterNot { it in first }
        val grouped = remaining.groupBy { text ->
            buildSet {
                var offset = 0
                while (offset < text.length) {
                    val point = text.codePointAt(offset)
                    if (Character.isLetter(point)) add(Character.UnicodeScript.of(point).name)
                    offset += Character.charCount(point)
                }
            }.sorted().joinToString("+")
        }
        val planned = grouped.values.flatMap { group -> batches(group.filter { it.length <= MAX_BATCH_CHARS }.map { TextSlot(0, false, it, false) }) }
        diagnostic(cacheKey, target, "segments=${segments.size} unique=${texts.size} batches=${anchors.size + planned.size}")
        val unmappedPronunciations = mutableSetOf<String>()
        parallel(planned) { batch -> requestBatch(batch, target) }.forEach { (batch, answer) ->
            val pronunciationBody = answer?.pronunciations.orEmpty().joinToString("")
            val pronunciationParts = pronunciationBody.split(markerRegex).map(String::trim)
            val mappedPronunciations = batch.slots.size > 1 && pronunciationBody.isNotEmpty() &&
                markerRegex.findAll(pronunciationBody).map { it.value }.toList() == (1 until batch.slots.size).map(::marker)
            if (batch.slots.size > 1 && pronunciationBody.isNotEmpty() && !mappedPronunciations) {
                // Without boundaries, attribution to one member would weaken the pronunciation guard.
                unmappedPronunciations += batch.slots.map { it.text }
            }
            batch.slots.forEachIndexed { index, slot ->
                first[slot.text] = answer?.copy(translations = listOf(answer.translations[index]),
                    sourceLanguage = if (batch.slots.size == 1) answer.sourceLanguage else "",
                    confidence = if (batch.slots.size == 1) answer.confidence else null,
                    pronunciations = if (mappedPronunciations) listOf(pronunciationParts[index]) else answer.pronunciations)
            }
        }
        // Only ambiguous/echoed members need individual detection; successful members stay batched.
        val ambiguous = planned.filter { it.slots.size > 1 }.flatMap { it.slots }.map { it.text }
            .filter { first[it] != null && (it in unmappedPronunciations || !usableTranslation(first[it], it, target)) }
        parallel(ambiguous) { text -> requestSegment(text, target) }.forEach { (text, answer) -> first[text] = answer }
        val sources = segments.map { reliableSource(first[it.text], it.text) }
        val contextSources = segments.indices.map { index ->
            sources[index].takeUnless { slots[segments[index].slot].sectionHeader }
        }

        val retryHints = segments.mapIndexed { index, segment ->
            val source = sources[index]
            val context = if (source == null && !slots[segment.slot].sectionHeader) {
                val left = (index - 1 downTo maxOf(0, index - 2)).firstNotNullOfOrNull { contextSources[it] }
                val right = (index + 1..minOf(segments.lastIndex, index + 2)).firstNotNullOfOrNull { contextSources[it] }
                left?.takeIf { it == right }
            } else null
            when {
                // A valid batched result has no individual language tag, not an uncertain
                // detection. Neighbor language must not override that usable auto translation.
                context != null && (first[segment.text]?.sourceLanguage?.isNotBlank() == true ||
                    !usableTranslation(first[segment.text], segment.text, target)) -> context
                source != null && !usableTranslation(first[segment.text], segment.text, target) -> source
                else -> null
            }
        }
        val retries = segments.indices.mapNotNull { index ->
            retryHints[index]?.let { segments[index].text to it }
        }.distinct()
        val retried = parallel(retries) { (text, source) -> requestSegment(text, target, source) }.toMap()
        val resolved = segments.mapIndexed { index, segment ->
            val hint = retryHints[index]
            val retry = hint?.let { retried[segment.text to it] }
            val answer = retry?.takeIf { usableTranslation(it, segment.text, target) } ?: first[segment.text]
            val source = reliableSource(answer, segment.text)
            val same = source != null && sameLanguage(source, target) && plausibleTarget(segment.text, target)
            when {
                same -> SegmentAnswer(segment.text, source, same = true, complete = true)
                usableTranslation(answer, segment.text, target) -> SegmentAnswer(answer!!.translations.single(), source, same = false, complete = true)
                else -> SegmentAnswer(segment.text, source, same = false, complete = false)
            }
        }
        val allSame = resolved.all { it.same }
        diagnostic(cacheKey, target, "resolved=${resolved.count { it.complete }} failed=${resolved.count { !it.complete }} same=${resolved.count { it.same }}")
        if (!allSame && resolved.none { it.complete && !it.same }) return null
        val languages = resolved.mapNotNull { it.source }.distinct()
        val source = if (resolved.any { it.source == null }) "und" else languages.singleOrNull() ?: "mul"
        val translated = slots.mapIndexed { index, slot ->
            buildString {
                var cursor = 0
                segments.indices.filter { segments[it].slot == index }.forEach { position ->
                    val segment = segments[position]
                    append(slot.text, cursor, segment.start)
                    append(resolved[position].text)
                    cursor = segment.end
                }
                append(slot.text, cursor, slot.text.length)
            }
        }
        val entry = CachedTranslation(
            version = TRANSLATION_VERSION,
            sourceLanguage = source,
            targetLanguage = target,
            texts = translated,
            allSameLanguage = allSame,
        )
        currentCoroutineContext().ensureActive()
        // A failed segment must remain eligible for a fresh attempt next time.
        if (resolved.all { it.complete }) {
            memory.put(cacheKey, entry)
            writeCache(cacheKey, entry)
        }
        return entry
    }

    private suspend fun <T, R> parallel(values: List<T>, request: suspend (T) -> R): List<Pair<T, R>> = coroutineScope {
        val permits = Semaphore(MAX_PARALLEL_REQUESTS)
        values.map { value -> async { permits.withPermit { value to request(value) } } }.awaitAll()
    }

    private suspend fun requestSegment(text: String, target: String, source: String = "auto"): BatchAnswer? =
        if (text.length > MAX_BATCH_CHARS) null
        else requestBatch(Batch(listOf(TextSlot(0, false, text, false)), text), target, source)

    private fun reliableSource(answer: BatchAnswer?, text: String): String? {
        val language = answer?.sourceLanguage?.takeIf { it.matches(Regex("[a-zA-Z]{2,3}([_-][a-zA-Z0-9]{2,8})*")) }
            ?.let(::canonicalLanguage)?.takeUnless { it in setOf("und", "mul") } ?: return null
        val confidence = answer.confidence
        if (confidence != null && (!confidence.isFinite() || confidence < 0.8 || confidence > 1.0)) return null
        if (confidence == null && Regex("\\p{L}+(?:['’]\\p{L}+)*").findAll(text).count() <= 2) return null
        return language
    }

    private fun usableTranslation(answer: BatchAnswer?, original: String, target: String): Boolean {
        val candidate = answer?.translations?.singleOrNull()?.takeIf(String::isNotBlank) ?: return false
        if (answer.pronunciations.any { comparable(it) == comparable(candidate) }) return false
        if (comparable(answer.pronunciations.joinToString("")) == comparable(candidate)) return false
        if (!plausibleTarget(candidate, target)) return false
        if (comparable(candidate) != comparable(original)) return true
        return reliableSource(answer, original)?.let { sameLanguage(it, target) } == true
    }

    private fun plausibleTarget(text: String, target: String): Boolean =
        canonicalLanguage(target) != "th" || text.none(Char::isLetter) || text.codePoints().anyMatch {
            Character.UnicodeScript.of(it) == Character.UnicodeScript.THAI
        }

    private fun segments(slot: Int, text: String): List<Segment> = buildList {
        // Latin is a script boundary, never a claim that the language is English.
        fun script(codePoint: Int): String = when (val value = Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> "CJK"
            else -> value.name
        }
        fun addRange(start: Int, end: Int) {
            var left = start
            var right = end
            while (left < right && text[left].isWhitespace()) left++
            while (right > left && text[right - 1].isWhitespace()) right--
            if (left < right && text.substring(left, right).any(Char::isLetter)) add(Segment(slot, left, right, text.substring(left, right)))
        }
        Regex("[^,;:!?。！？；：\\n/]+").findAll(text).forEach { clause ->
            var start = clause.range.first
            var offset = start
            var previous: String? = null
            while (offset <= clause.range.last) {
                val point = text.codePointAt(offset)
                if (Character.isLetter(point)) {
                    val current = script(point)
                    if (previous != null && previous != current) {
                        addRange(start, offset)
                        start = offset
                    }
                    previous = current
                }
                offset += Character.charCount(point)
            }
            addRange(start, clause.range.last + 1)
        }
    }

    suspend fun romanize(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): RomanizationResult {
        if (lines.isEmpty()) return RomanizationResult.Unavailable
        val slots = flatten(lines)
        if (slots.isEmpty()) return RomanizationResult.Unavailable
        if (!hasNonLatinLetters(slots)) return RomanizationResult.AlreadyRomanized

        // Romanization always ends in Latin script. The translation destination
        // is still sent as `tl` because the web endpoint requires it, but it is
        // deliberately excluded from this cache key: changing "translate to"
        // cannot change how the source language is pronounced.
        val cacheKey = cacheKey(trackId, "romanize", slots)
        val cached = memory.get(cacheKey) ?: readCache(cacheKey)?.also {
            memory.put(cacheKey, it)
        }
        if (cached != null && cached.version == CACHE_VERSION && cached.texts.size == slots.size) {
            return RomanizationResult.Romanized(
                lines = rebuild(lines, slots, cached.texts),
                sourceLanguage = cached.sourceLanguage,
                fromCache = true,
            )
        }

        val target = targetLanguageTag.trim().ifBlank { "en" }
        val answer = try {
            requests.run("romanize:$cacheKey") {
                cachedText(cacheKey, slots.size)?.let { ResolvedText(it, true) }
                    ?: fetchRomanization(cacheKey, target, slots)?.let { ResolvedText(it, false) }
            }
        } catch (limited: LyricsTranslationCooldown) {
            diagnostic(cacheKey, target, "operation=romanize outcome=rate_limited retry_after_ms=${limited.retryAfterMillis}")
            return RomanizationResult.RateLimited(limited.retryAfterMillis)
        } ?: return providerRequests.cooldown()?.takeIf { it.rateLimited }
            ?.let { RomanizationResult.RateLimited(it.retryAfterMillis) } ?: RomanizationResult.Unavailable
        return RomanizationResult.Romanized(rebuild(lines, slots, answer.entry.texts), answer.entry.sourceLanguage, answer.fromCache)
    }

    private suspend fun fetchRomanization(cacheKey: String, target: String, slots: List<TextSlot>): CachedTranslation? {
        val batches = batches(slots)
        val answers = coroutineScope {
            batches.chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
                group.map { batch -> async { requestRomanizationBatch(batch, target) } }.awaitAll()
            }
        }
        if (answers.any { it == null }) return null
        val complete = answers.filterNotNull()
        val source = complete
            .groupBy { canonicalLanguage(it.sourceLanguage) }
            .maxByOrNull { (_, values) -> values.sumOf { it.sourceWeight } }
            ?.key
            .orEmpty()
        val romanized = complete.flatMap { it.translations }
        if (source.isBlank() || romanized.size != slots.size) return null
        if (unchangedWeight(slots, romanized) == slots.sumOf { it.text.length }) {
            // Non-Latin input reached this point, so an unchanged response is
            // an unsupported/failed romanization rather than an already-Latin
            // lyric. The fast all-Latin check above handles the real no-op case.
            return null
        }

        val entry = CachedTranslation(
            sourceLanguage = source,
            targetLanguage = "Latn",
            texts = romanized,
        )
        currentCoroutineContext().ensureActive()
        memory.put(cacheKey, entry)
        writeCache(cacheKey, entry)
        return entry
    }

    private fun flatten(lines: List<LyricLine>): List<TextSlot> = buildList {
        lines.forEachIndexed { index, line ->
            if (line.text.isNotBlank()) {
                val header = Genius.isSectionHeader(line.text)
                add(
                    TextSlot(
                        lineIndex = index,
                        background = false,
                        text = if (header) line.text.removePrefix("[").removeSuffix("]").trim() else line.text,
                        sectionHeader = header,
                    ),
                )
            }
            line.background?.takeIf { it.text.isNotBlank() }?.let { background ->
                add(TextSlot(index, background = true, background.text, sectionHeader = false))
            }
        }
    }

    private fun batches(slots: List<TextSlot>): List<Batch> {
        val result = mutableListOf<Batch>()
        var current = mutableListOf<TextSlot>()
        var length = 0

        fun flush() {
            if (current.isEmpty()) return
            result += Batch(current.toList(), payload(current))
            current = mutableListOf()
            length = 0
        }

        slots.forEach { slot ->
            val added = slot.text.length + if (current.isEmpty()) 0 else 8
            if (current.isNotEmpty() && length + added > MAX_BATCH_CHARS) flush()
            current += slot
            length += added
        }
        flush()
        return result
    }

    private fun payload(slots: List<TextSlot>): String = buildString {
        slots.forEachIndexed { index, slot ->
            if (index > 0) append('\n').append(marker(index)).append('\n')
            append(slot.text)
        }
    }

    private fun marker(index: Int): String = "\uE000${index.toString().padStart(4, '0')}\uE001"

    private suspend fun requestBatch(
        batch: Batch,
        target: String,
        sourceLanguage: String = "auto",
    ): BatchAnswer? {
        val body = FormBody.Builder()
            .add("client", "dict-chrome-ex")
            .add("sl", sourceLanguage)
            .add("tl", target)
            .add("dt", "t")
            .add("q", batch.payload)
            .build()
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", "BitChord/1.7.1")
            .header("Accept", "application/json")
            .post(body)
            .build()
        val response = try {
            executeRequest(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            val cooldown = error as? LyricsTranslationCooldown
            val reason = (cooldown?.status ?: (error as? LyricsTranslationHttpException)?.status)?.let { "http_$it" } ?: "io"
            diagnostic("provider", target, "outcome=${if (cooldown?.rateLimited == true) "rate_limited" else "unavailable"} reason=$reason retry_after_ms=${cooldown?.retryAfterMillis ?: 0}")
            if (cooldown?.rateLimited == true) throw cooldown
            return null
        }
        return runCatching {
            val root = json.parseToJsonElement(response).jsonArray
            val translatedBody = root[0].jsonArray.joinToString(separator = "") { segment ->
                segment.jsonArray.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            val source = root.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty()
            if (markerRegex.findAll(translatedBody).map { it.value }.toList() != (1 until batch.slots.size).map(::marker)) {
                diagnostic("provider", target, "outcome=unavailable reason=marker_mismatch")
                return@runCatching null
            }
            val parts = translatedBody.split(markerRegex).map(String::trim)
            if (parts.size != batch.slots.size || parts.any { it.isBlank() }) {
                diagnostic("provider", target, "outcome=unavailable reason=empty_response")
                return@runCatching null
            }
            val pronunciations = root[0].jsonArray.mapNotNull { segment ->
                segment.jsonArray.getOrNull(3)?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            }
            BatchAnswer(parts, source, batch.payload.length,
                confidence = root.getOrNull(6)?.jsonPrimitive?.doubleOrNull,
                pronunciations = pronunciations,
            )
        }.onFailure { diagnostic("provider", target, "outcome=unavailable reason=malformed_response") }.getOrNull()
    }

    private suspend fun requestRomanizationBatch(batch: Batch, target: String): BatchAnswer? {
        val body = FormBody.Builder()
            .add("client", "dict-chrome-ex")
            .add("sl", "auto")
            .add("tl", target)
            .add("dt", "rm")
            .add("q", batch.payload)
            .build()
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", "BitChord/1.7.1")
            .header("Accept", "application/json")
            .post(body)
            .build()
        val response = try {
            executeRequest(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            val cooldown = error as? LyricsTranslationCooldown
            diagnostic("provider", target, "operation=romanize outcome=${if (cooldown?.rateLimited == true) "rate_limited" else "unavailable"} reason=${if (cooldown?.rateLimited == true) "http_429" else "io"}")
            if (cooldown?.rateLimited == true) throw cooldown
            return null
        }
        return runCatching {
            val root = json.parseToJsonElement(response).jsonArray
            val romanizedBody = root[0].jsonArray.joinToString(separator = "") { segment ->
                segment.jsonArray.getOrNull(3)?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            val source = root.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty()
            val parts = romanizedBody.split(markerRegex).map(String::trim)
            if (source.isBlank() || parts.size != batch.slots.size || parts.any { it.isBlank() }) {
                return@runCatching null
            }
            BatchAnswer(parts, source, batch.payload.length)
        }.getOrNull()
    }

    private fun hasNonLatinLetters(slots: List<TextSlot>): Boolean = slots.any { slot ->
        slot.text.codePoints().anyMatch { codePoint ->
            Character.isLetter(codePoint) &&
                Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN
        }
    }

    private fun unchangedWeight(slots: List<TextSlot>, transformed: List<String>): Int =
        slots.zip(transformed).sumOf { (slot, text) ->
            if (comparable(slot.text) == comparable(text)) slot.text.length else 0
        }

    private fun comparable(text: String): String = text
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")

    private suspend fun Call.awaitBody(): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = try { readLyricsTranslationResponse(response) } catch (error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                    return
                }
                if (continuation.isActive) continuation.resume(body)
            }
        })
    }

    private fun rebuild(
        original: List<LyricLine>,
        slots: List<TextSlot>,
        translated: List<String>,
    ): List<LyricLine> {
        val byLine = slots.zip(translated).groupBy { it.first.lineIndex }
        return original.mapIndexed { index, line ->
            val entries = byLine[index].orEmpty()
            val lead = entries.firstOrNull { !it.first.background }
            val backing = entries.firstOrNull { it.first.background }
            val leadText = lead?.let { (slot, text) -> if (slot.sectionHeader) "[$text]" else text }
                ?: line.text
            line.copy(
                text = leadText,
                words = retimeWords(line, leadText),
                timingSource = line.takeIf { it.isWordSynced },
                background = line.background?.let { source ->
                    val text = backing?.second ?: source.text
                    source.copy(
                        text = text,
                        words = retimeWords(source, text),
                        timingSource = source.takeIf { it.isWordSynced },
                    )
                },
            )
        }
    }

    /**
     * Keep the source vocal bounds without inventing translated word timings.
     * LyricLine.timingSource projects the original non-uniform character sweep
     * onto the new text, preserving holds, pauses and the original glow envelope.
     */
    private fun retimeWords(source: LyricLine, translated: String): List<LyricWord> {
        if (source.words.isEmpty()) return emptyList()
        if (translated.isEmpty()) return emptyList()
        val start = source.words.first().startMs
        return listOf(LyricWord(start, source.words.last().endMs, translated))
    }

    private fun canonicalLanguage(tag: String): String = when (
        Locale.forLanguageTag(tag.replace('_', '-')).language.lowercase(Locale.ROOT)
    ) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        else -> Locale.forLanguageTag(tag.replace('_', '-')).language.lowercase(Locale.ROOT)
    }

    private fun sameLanguage(first: String, second: String): Boolean =
        canonicalLanguage(first) == canonicalLanguage(second)

    private fun cacheKey(trackId: String, target: String, slots: List<TextSlot>, translation: Boolean = false): String {
        val source = buildString {
            append(trackId).append('\u0000').append(target)
            slots.forEach {
                append('\u0000')
                if (translation) append(if (it.sectionHeader) "header:" else "lyric:")
                append(it.text)
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun readCache(key: String, version: Int = CACHE_VERSION): CachedTranslation? =
        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                val root = cacheDir ?: return@withLock null
                val file = File(File(root, CACHE_DIRECTORY), "$key.json.gz")
                if (!file.isFile) return@withLock null
                runCatching {
                    val value = GZIPInputStream(FileInputStream(file)).bufferedReader().use {
                        json.decodeFromString<CachedTranslation>(it.readText())
                    }
                    file.setLastModified(System.currentTimeMillis())
                    value.takeIf { it.version == version }
                }.getOrNull()
            }
        }

    private suspend fun writeCache(key: String, value: CachedTranslation) =
        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                val root = cacheDir ?: return@withLock
                val directory = File(root, CACHE_DIRECTORY)
                if (!directory.exists() && !directory.mkdirs()) return@withLock
                val destination = File(directory, "$key.json.gz")
                val temporary = File(directory, "$key.tmp")
                runCatching {
                    GZIPOutputStream(FileOutputStream(temporary)).bufferedWriter().use {
                        it.write(json.encodeToString(value))
                    }
                    if (!temporary.renameTo(destination)) {
                        temporary.copyTo(destination, overwrite = true)
                        temporary.delete()
                    }
                    trimCache(directory)
                }.onFailure { temporary.delete() }
            }
        }

    private fun trimCache(directory: File) {
        val files = directory.listFiles { file -> file.extension == "gz" }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        var kept = 0L
        files.forEach { file ->
            kept += file.length()
            if (kept > MAX_CACHE_BYTES) file.delete()
        }
    }
}
