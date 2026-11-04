package com.music.bitchord.data.lyrics

import java.util.UUID
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.Request
import kotlin.test.*

class LyricsMultilingualTranslationTest {
    private data class Sent(val text: String, val source: String, val operation: String, val host: String)

    private fun sent(request: Request): Sent {
        val form = request.body as FormBody
        fun value(name: String) = (0 until form.size).firstOrNull { form.name(it) == name }?.let(form::value).orEmpty()
        return Sent(value("q"), value("sl"), value("dt"), request.url.host)
    }

    private fun answer(text: String, source: String, confidence: Double = 1.0, pronunciation: String? = null): String =
        buildJsonArray {
            add(buildJsonArray {
                add(buildJsonArray {
                    add(text); add(JsonNull); add(JsonNull)
                    add(pronunciation?.let(::JsonPrimitive) ?: JsonNull)
                })
            })
            add(JsonNull); add(source); add(JsonNull); add(JsonNull); add(JsonNull); add(confidence)
        }.toString()

    private suspend fun isolated(block: suspend () -> Unit) {
        val oldTransport = LyricsTranslation.transport
        val oldCache = LyricsTranslation.cacheDir
        try {
            LyricsTranslation.cacheDir = null
            block()
        } finally {
            LyricsTranslation.transport = oldTransport
            LyricsTranslation.cacheDir = oldCache
        }
    }

    private suspend fun translate(lines: List<LyricLine>, track: String = UUID.randomUUID().toString()) =
        LyricsTranslation.translate(track, lines, "th")

    @Test fun routesEnglishFrenchAndMixedScriptSegmentsIndependently() = runBlocking { isolated {
        val fixtures = mapOf(
            "I love you" to ("en" to "ฉันรักคุณ"),
            "Je m'allonge sur le tapis" to ("fr" to "ฉันเอนตัวลงบนพรม"),
            "君が好き" to ("ja" to "ฉันชอบเธอ"),
            "ฉันรักเธอ" to ("th" to "ฉันรักเธอ"),
        )
        val seen = Collections.synchronizedList(mutableListOf<Sent>())
        LyricsTranslation.transport = { request ->
            val item = sent(request)
            seen += item
            fixtures[item.text]?.let { (source, text) -> answer(text, source) }
                ?: answer(item.text, "th")
        }
        val cases = listOf(
            listOf("I love you"),
            listOf("Je m'allonge sur le tapis"),
            listOf("I love you", "Je m'allonge sur le tapis", "I love you"),
            listOf("Je m'allonge sur le tapis; I love you"),
            listOf("君が好き", "I love you"),
            listOf("君が好き I love you"),
            listOf("ฉันรักเธอ", "I love you", "ฉันรักเธอ"),
        )
        for ((index, texts) in cases.withIndex()) {
            val result = assertIs<LyricsTranslation.Result.Translated>(translate(texts.mapIndexed { index, text -> LyricLine(index * 1000L, text) }))
            val expected = texts.map { text -> fixtures.entries.fold(text) { value, (original, pair) -> value.replace(original, pair.second) } }
            assertEquals(expected, result.lines.map { it.text }, texts.toString())
            assertEquals(texts.indices.map { it * 1000L }, result.lines.map { it.timeMs })
            assertEquals(when (index) { 0 -> "en"; 1 -> "fr"; else -> "mul" }, result.sourceLanguage)
        }
        assertTrue(seen.all { it.source == "auto" && it.operation == "t" })
        assertTrue(seen.all { it.host == "translate.googleapis.com" })
    } }

    @Test fun sameLanguageRequiresEveryEligibleSegmentToMatchTarget() = runBlocking { isolated {
        LyricsTranslation.transport = { request ->
            val text = sent(request).text
            if (text == "I love you") answer("ฉันรักคุณ", "en") else answer(text, "th")
        }
        assertIs<LyricsTranslation.Result.SameLanguage>(translate(listOf(LyricLine(0, "ฉันรักเธอ"))))
        val mixed = assertIs<LyricsTranslation.Result.Translated>(translate(listOf(LyricLine(0, "ฉันรักเธอ"), LyricLine(1, "I love you"))))
        assertEquals(listOf("ฉันรักเธอ", "ฉันรักคุณ"), mixed.lines.map { it.text })
    } }

    @Test fun shortUncertainSegmentUsesOnlyAgreeingReliableNeighbors() = runBlocking { isolated {
        val seen = Collections.synchronizedList(mutableListOf<Sent>())
        LyricsTranslation.transport = { request ->
            val item = sent(request); seen += item
            when (item.text) {
                "Je reste avec toi" -> answer("ฉันอยู่กับเธอ", "fr")
                "Je reviens demain" -> answer("ฉันจะกลับมาพรุ่งนี้", "fr")
                "Oui" -> if (item.source == "fr") answer("ใช่", "fr") else answer("วี", "en", confidence = 0.1)
                else -> answer(item.text, "th")
            }
        }
        val result = assertIs<LyricsTranslation.Result.Translated>(translate(listOf("Je reste avec toi", "Oui", "Je reviens demain").map { LyricLine(0, it) }))
        assertEquals("ใช่", result.lines[1].text)
        assertTrue(seen.any { it.text == "Oui" && it.source == "fr" })
        assertTrue(seen.none { it.source == "en" })
    } }

    @Test fun conflictingNeighborsDoNotForceAnUncertainLatinLanguage() = runBlocking { isolated {
        val seen = Collections.synchronizedList(mutableListOf<Sent>())
        LyricsTranslation.transport = { request ->
            val item = sent(request); seen += item
            when (item.text) {
                "Je reste avec toi" -> answer("ฉันอยู่กับเธอ", "fr")
                "Love stays with me" -> answer("ความรักอยู่กับฉัน", "en")
                "Oh" -> answer("Oh", "", confidence = 0.0)
                else -> answer(item.text, "th")
            }
        }
        val result = assertIs<LyricsTranslation.Result.Translated>(translate(listOf("Je reste avec toi", "Oh", "Love stays with me").map { LyricLine(0, it) }))
        assertEquals("Oh", result.lines[1].text)
        assertEquals("und", result.sourceLanguage)
        assertTrue(seen.filter { it.text == "Oh" }.all { it.source == "auto" })
    } }

    @Test fun unusableHintedRetryKeepsTheUsableUncertainAutoTranslation() = runBlocking { isolated {
        for (retry in listOf("{malformed", answer("Oui", "fr"))) {
            val seen = Collections.synchronizedList(mutableListOf<Sent>())
            LyricsTranslation.transport = { request ->
                val item = sent(request); seen += item
                when (item.text) {
                    "Je reste avec toi" -> answer("ฉันอยู่กับเธอ", "fr")
                    "Je reviens demain" -> answer("ฉันจะกลับมาพรุ่งนี้", "fr")
                    "Oui" -> if (item.source == "fr") retry else answer("ใช่", "en", confidence = 0.1)
                    else -> error("Unexpected request: $item")
                }
            }
            val result = assertIs<LyricsTranslation.Result.Translated>(translate(listOf("Je reste avec toi", "Oui", "Je reviens demain").map { LyricLine(0, it) }))
            assertEquals("ใช่", result.lines[1].text)
            assertEquals("und", result.sourceLanguage)
            assertTrue(seen.any { it.text == "Oui" && it.source == "fr" })
        }
    } }

    @Test fun explicitPronunciationMetadataIsNotAcceptedAsMeaning() = runBlocking { isolated {
        val seen = Collections.synchronizedList(mutableListOf<Sent>())
        LyricsTranslation.transport = { request ->
            val item = sent(request); seen += item
            if (item.source == "fr") answer("ฉันเอนตัวลงบนพรม", "fr")
            else answer("เฌอ มาลองจ์ ซูร์ เลอ ตาปี", "fr", pronunciation = "เฌอ มาลองจ์ ซูร์ เลอ ตาปี")
        }
        val result = assertIs<LyricsTranslation.Result.Translated>(translate(listOf(LyricLine(0, "Je m'allonge sur le tapis"))))
        assertEquals("ฉันเอนตัวลงบนพรม", result.lines.single().text)
        assertEquals(listOf("auto", "fr"), seen.map { it.source })
        assertTrue(seen.all { it.operation == "t" && it.host == "translate.googleapis.com" })
    } }

    @Test fun joinedPronunciationMetadataAcrossProviderSegmentsIsNotAcceptedAsMeaning() = runBlocking { isolated {
        val seen = Collections.synchronizedList(mutableListOf<Sent>())
        LyricsTranslation.transport = { request ->
            val item = sent(request); seen += item
            if (item.source == "fr") answer("ฉันเอนตัวลงบนพรม", "fr")
            else buildJsonArray {
                add(buildJsonArray {
                    for (part in listOf("เฌอ ", "มาลองจ์ ซูร์ เลอ ตาปี")) {
                        add(buildJsonArray { add(part); add(JsonNull); add(JsonNull); add(part) })
                    }
                })
                add(JsonNull); add("fr"); add(JsonNull); add(JsonNull); add(JsonNull); add(1.0)
            }.toString()
        }
        val result = assertIs<LyricsTranslation.Result.Translated>(translate(listOf(LyricLine(0, "Je m'allonge sur le tapis"))))
        assertEquals("ฉันเอนตัวลงบนพรม", result.lines.single().text)
        assertEquals(listOf("auto", "fr"), seen.map { it.source })
    } }

    @Test fun malformedAndUnchangedForeignSegmentsKeepTheirOwnOriginalText() = runBlocking { isolated {
        LyricsTranslation.transport = { request ->
            when (val text = sent(request).text) {
                "I love you" -> answer("ฉันรักคุณ", "en")
                "Je reste avec toi" -> "{bad response"
                "Je reviens demain" -> answer(text, "fr")
                else -> answer(text, "th")
            }
        }
        val source = listOf("Je reste avec toi", "I love you", "Je reviens demain").mapIndexed { index, text -> LyricLine(index.toLong(), text) }
        val result = assertIs<LyricsTranslation.Result.Translated>(translate(source))
        assertEquals(listOf("Je reste avec toi", "ฉันรักคุณ", "Je reviens demain"), result.lines.map { it.text })
        assertEquals(source.map { it.timeMs }, result.lines.map { it.timeMs })
    } }

    @Test fun unchangedForeignResponseIsNotSuccessfulTranslation() = runBlocking { isolated {
        val seen = Collections.synchronizedList(mutableListOf<Sent>())
        LyricsTranslation.transport = { request ->
            val item = sent(request); seen += item
            answer(item.text, "fr")
        }
        assertIs<LyricsTranslation.Result.Unavailable>(translate(listOf(LyricLine(0, "Je reviens demain"))))
        assertTrue(seen.all { it.host == "translate.googleapis.com" && it.operation == "t" })
    } }

    @Test fun preservesSectionHeadersVocalAlignmentAndOriginalTimingSources() = runBlocking { isolated {
        LyricsTranslation.transport = { request ->
            when (val text = sent(request).text) {
                "I love you" -> answer("ฉันรักคุณ", "en")
                "Je reste avec toi" -> answer("ฉันอยู่กับเธอ", "fr")
                "Chorus" -> answer("ท่อนฮุก", "en")
                else -> answer(text, "th")
            }
        }
        val backing = LyricLine(120, "Je reste avec toi", words = listOf(LyricWord(120, 900, "Je reste avec toi")), alignment = LyricAlignment.End)
        val syllables = listOf(LyricSyllable(500, 550, 0, 2), LyricSyllable(700, 1000, 2, 8))
        val lead = LyricLine(100, "I love you", words = listOf(LyricWord(100, 300, "I"), LyricWord(500, 1000, "love you", syllables)), sungUntilMs = 1200, background = backing, alignment = LyricAlignment.End)
        val source = listOf(LyricLine(0, "[Chorus]"), lead)
        val result = assertIs<LyricsTranslation.Result.Translated>(translate(source))
        assertEquals("[ท่อนฮุก]", result.lines.first().text)
        val line = result.lines[1]
        assertEquals("ฉันรักคุณ", line.text)
        assertEquals(lead.alignment, line.alignment)
        assertEquals(lead.timeMs, line.timeMs)
        assertEquals(lead.sungUntilMs, line.sungUntilMs)
        assertSame(lead, line.timingSource)
        assertEquals("ฉันอยู่กับเธอ", line.background!!.text)
        assertEquals(backing.alignment, line.background!!.alignment)
        assertSame(backing, line.background!!.timingSource)
        for (position in listOf(50L, 100L, 200L, 400L, 525L, 625L, 850L, 1100L)) {
            assertEquals(
                lead.revealedChars(position) / lead.text.length,
                line.revealedChars(position) / line.text.length,
                absoluteTolerance = 0.0001f,
                message = "Source syllable sweep at $position ms",
            )
        }
        assertEquals(line.revealedChars(600), line.revealedChars(675))
        assertEquals("I love you", lead.text)
        assertEquals(2, lead.words.size)
        assertEquals(syllables, lead.words.last().syllables)
    } }

    @Test fun sectionHeaderOnlyLyricsStillTranslateTheirInnerTextAndKeepBrackets() = runBlocking { isolated {
        LyricsTranslation.transport = { request ->
            assertEquals("Chorus", sent(request).text)
            answer("ท่อนฮุก", "en")
        }
        val result = assertIs<LyricsTranslation.Result.Translated>(translate(listOf(LyricLine(100, "[Chorus]"))))
        assertEquals("[ท่อนฮุก]", result.lines.single().text)
        assertEquals(100L, result.lines.single().timeMs)
    } }

    @Test fun keepsTwoRequestsActiveWithoutWaitingForTheSlowerMemberOfAPair() = runBlocking { isolated {
        val firstStarted = CompletableDeferred<Unit>()
        val thirdStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        LyricsTranslation.transport = { request ->
            val count = active.incrementAndGet()
            peak.accumulateAndGet(count, ::maxOf)
            try {
                when (sent(request).text) {
                    "First line stays" -> { firstStarted.complete(Unit); releaseFirst.await() }
                    "Third line starts" -> thirdStarted.complete(Unit)
                }
                answer("แปลแล้ว", "en")
            } finally { active.decrementAndGet() }
        }
        val result = async { translate(listOf("First line stays", "Second line moves", "Third line starts").map { LyricLine(0, it) }) }
        withTimeout(3000) { firstStarted.await() }
        val thirdStartedWhileFirstHeld = try {
            withTimeoutOrNull(2000) { thirdStarted.await(); true } == true
        } finally { releaseFirst.complete(Unit) }
        assertIs<LyricsTranslation.Result.Translated>(result.await())
        assertTrue(thirdStartedWhileFirstHeld, "The freed second request slot should start the third segment")
        assertEquals(2, peak.get())
        assertEquals(0, active.get())
    } }

    @Test fun repeatedSegmentsAndConcurrentCallersShareTransportButRebuildTheirOwnTimings() = runBlocking { isolated {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val seen = Collections.synchronizedList(mutableListOf<Sent>())
        LyricsTranslation.transport = { request ->
            val item = sent(request); seen += item
            started.complete(Unit); release.await()
            answer("ฉันรักคุณ", "en")
        }
        val track = UUID.randomUUID().toString()
        val first = async(start = CoroutineStart.UNDISPATCHED) { translate(listOf(LyricLine(1, "I love you"), LyricLine(2, "I love you")), track) }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { translate(listOf(LyricLine(10, "I love you"), LyricLine(20, "I love you")), track) }
        release.complete(Unit)
        assertEquals(listOf(1L, 2L), assertIs<LyricsTranslation.Result.Translated>(first.await()).lines.map { it.timeMs })
        assertEquals(listOf(10L, 20L), assertIs<LyricsTranslation.Result.Translated>(second.await()).lines.map { it.timeMs })
        assertEquals(1, seen.size)
        assertEquals("I love you", seen.single().text)
    } }

    @Test fun switchingTracksCancelsObsoleteTranslationWithoutCachingOrMixingItsAnswer() = runBlocking { isolated {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val obsoleteTrack = UUID.randomUUID().toString()
        LyricsTranslation.transport = { request ->
            if (sent(request).text == "Je reste avec toi") {
                try { started.complete(Unit); awaitCancellation() }
                finally { cancelled.complete(Unit) }
            } else answer("ฉันรักคุณ", "en")
        }
        val obsolete = async { translate(listOf(LyricLine(1, "Je reste avec toi")), obsoleteTrack) }
        withTimeout(3000) { started.await() }
        obsolete.cancelAndJoin()
        withTimeout(3000) { cancelled.await() }
        val current = assertIs<LyricsTranslation.Result.Translated>(translate(listOf(LyricLine(20, "I love you"))))
        assertEquals("ฉันรักคุณ", current.lines.single().text)
        LyricsTranslation.transport = { answer("ฉันอยู่กับเธอ", "fr") }
        val retried = assertIs<LyricsTranslation.Result.Translated>(translate(listOf(LyricLine(1, "Je reste avec toi")), obsoleteTrack))
        assertFalse(retried.fromCache)
        assertEquals("ฉันอยู่กับเธอ", retried.lines.single().text)
    } }

    @Test fun genuineRomanizationRemainsADistinctOperation() = runBlocking { isolated {
        val seen = Collections.synchronizedList(mutableListOf<Sent>())
        LyricsTranslation.transport = { request ->
            seen += sent(request)
            answer("", "ja", pronunciation = "kimi ga suki")
        }
        val result = assertIs<LyricsTranslation.RomanizationResult.Romanized>(LyricsTranslation.romanize(UUID.randomUUID().toString(), listOf(LyricLine(0, "君が好き")), "th"))
        assertEquals("kimi ga suki", result.lines.single().text)
        assertEquals("rm", seen.single().operation)
    } }

    private fun cacheKey(track: String, operation: String, texts: List<String>, translation: Boolean = false): String {
        val input = buildString {
            append(track).append('\u0000').append(operation)
            texts.forEach { append('\u0000'); if (translation) append("lyric:"); append(it) }
        }
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test fun migratesOnlyTranslationIdentityAndKeepsLegacyRomanizationAndOtherData() = runBlocking { isolated {
        val directory = Files.createTempDirectory("bitchord-multilingual-cache-").toFile()
        val cache = directory.resolve("lyrics_translation_v3").apply { mkdirs() }
        val track = UUID.randomUUID().toString()
        val french = "Je m'allonge sur le tapis"
        fun fixture(key: String, version: Int, source: String, target: String, output: String) = cache.resolve("$key.json.gz").also { file ->
            val value = buildJsonObject {
                put("version", version); put("sourceLanguage", source); put("targetLanguage", target)
                putJsonArray("texts") { add(output) }
            }
            GZIPOutputStream(file.outputStream()).use { it.write(value.toString().toByteArray()) }
        }
        val legacyTranslation = fixture(cacheKey(track, "th", listOf(french)), 3, "fr", "th", "old incorrect answer")
        val legacyBytes = legacyTranslation.readBytes()
        val romanTrack = UUID.randomUUID().toString()
        val roman = fixture(cacheKey(romanTrack, "romanize", listOf("君が好き")), 3, "ja", "Latn", "kimi ga suki")
        val romanBytes = roman.readBytes()
        val unrelated = directory.resolve("unrelated-cache").apply { writeText("keep me") }
        try {
            LyricsTranslation.cacheDir = directory
            val seen = Collections.synchronizedList(mutableListOf<Sent>())
            LyricsTranslation.transport = { request ->
                val item = sent(request); seen += item
                answer("ฉันเอนตัวลงบนพรม", "fr")
            }
            val result = assertIs<LyricsTranslation.Result.Translated>(translate(listOf(LyricLine(0, french)), track))
            assertFalse(result.fromCache)
            assertEquals("ฉันเอนตัวลงบนพรม", result.lines.single().text)
            val again = assertIs<LyricsTranslation.Result.Translated>(translate(listOf(LyricLine(500, french)), track))
            assertTrue(again.fromCache)
            assertEquals(500L, again.lines.single().timeMs)
            val romanized = assertIs<LyricsTranslation.RomanizationResult.Romanized>(LyricsTranslation.romanize(romanTrack, listOf(LyricLine(0, "君が好き")), "th"))
            assertTrue(romanized.fromCache)
            assertEquals("kimi ga suki", romanized.lines.single().text)
            assertEquals(1, seen.size)
            assertContentEquals(legacyBytes, legacyTranslation.readBytes())
            assertContentEquals(romanBytes, roman.readBytes())
            assertEquals("keep me", unrelated.readText())
            val wrongVersionTrack = UUID.randomUUID().toString()
            fixture(cacheKey(wrongVersionTrack, "translate:v4:th", listOf(french), translation = true), 3, "fr", "th", "wrong schema")
            assertFalse(assertIs<LyricsTranslation.Result.Translated>(translate(listOf(LyricLine(0, french)), wrongVersionTrack)).fromCache)
            LyricsTranslation.transport = { answer("I lie down on the carpet", "fr") }
            val otherTarget = assertIs<LyricsTranslation.Result.Translated>(LyricsTranslation.translate(track, listOf(LyricLine(0, french)), "en"))
            assertFalse(otherTarget.fromCache)
            assertEquals("I lie down on the carpet", otherTarget.lines.single().text)
        } finally { directory.deleteRecursively() }
    } }

    @Test fun cacheStaysBoundedWithoutRemovingOtherCacheDirectories() = runBlocking { isolated {
        val directory = Files.createTempDirectory("bitchord-multilingual-bounds-").toFile()
        val cache = directory.resolve("lyrics_translation_v3").apply { mkdirs() }
        val unrelated = directory.resolve("other-cache").apply { mkdirs() }.resolve("retained").apply { writeText("keep") }
        try {
            repeat(3) { index ->
                cache.resolve("old-$index.json.gz").apply {
                    writeBytes(ByteArray(1_048_576) { index.toByte() })
                    setLastModified(1000L + index)
                }
            }
            LyricsTranslation.cacheDir = directory
            LyricsTranslation.transport = { answer("ฉันรักคุณ", "en") }
            assertIs<LyricsTranslation.Result.Translated>(translate(listOf(LyricLine(0, "I love you"))))
            assertTrue(cache.listFiles()!!.filter { it.extension == "gz" }.sumOf { it.length() } <= 2L * 1024 * 1024)
            assertEquals("keep", unrelated.readText())
        } finally { directory.deleteRecursively() }
    } }

    @Test fun failedSegmentsAreNotFrozenInCacheAndOversizedSegmentsNeverReachTransport() = runBlocking { isolated {
        val track = UUID.randomUUID().toString()
        val source = listOf(LyricLine(0, "I love you"), LyricLine(1, "Je reste avec toi"))
        LyricsTranslation.transport = { request ->
            if (sent(request).text == "I love you") answer("ฉันรักคุณ", "en") else "bad json"
        }
        assertEquals("Je reste avec toi", assertIs<LyricsTranslation.Result.Translated>(translate(source, track)).lines[1].text)
        LyricsTranslation.transport = { request ->
            if (sent(request).text == "I love you") answer("ฉันรักคุณ", "en") else answer("ฉันอยู่กับเธอ", "fr")
        }
        val retried = assertIs<LyricsTranslation.Result.Translated>(translate(source, track))
        assertFalse(retried.fromCache)
        assertEquals("ฉันอยู่กับเธอ", retried.lines[1].text)
        val seen = Collections.synchronizedList(mutableListOf<Sent>())
        LyricsTranslation.transport = { request -> seen += sent(request); answer("ฉันรักคุณ", "en") }
        val oversized = "a".repeat(3501)
        val partial = assertIs<LyricsTranslation.Result.Translated>(translate(listOf(LyricLine(0, oversized), LyricLine(1, "I love you"))))
        assertEquals(oversized, partial.lines.first().text)
        assertTrue(seen.all { it.text.length <= 3500 })
    } }
}
