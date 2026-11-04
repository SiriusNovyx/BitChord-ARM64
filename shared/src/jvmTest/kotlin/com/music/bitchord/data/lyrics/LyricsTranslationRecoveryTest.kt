package com.music.bitchord.data.lyrics

import com.music.bitchord.data.DebugLog
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class LyricsTranslationRecoveryTest {
    @Test fun mixedBatchThatEchoesFrenchRetriesOnlyThoseMembersWithAutoDetection() = runBlocking { isolated {
        val source = listOf("The morning light", "Je reste avec toi", "We stay together", "Je reviens demain", "Love stays with me", "Our voices remain")
        val target = listOf("แสงยามเช้า", "ฉันอยู่กับเธอ", "เราอยู่ด้วยกัน", "ฉันจะกลับมาพรุ่งนี้", "ความรักอยู่กับฉัน", "เสียงของเรายังคงอยู่")
        val sent = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        LyricsTranslation.transport = { request ->
            val form = request.body as FormBody
            fun value(key: String) = (0 until form.size).first { form.name(it) == key }.let(form::value)
            val text = value("q"); sent += text to value("sl")
            val batched = '\uE000' in text
            val translated = source.indices.reversed().fold(text) { result, i ->
                if (batched && source[i].startsWith("Je ")) result else result.replace(source[i], target[i])
            }
            response(translated, if (!batched && text.startsWith("Je ")) "fr" else "en")
        }
        val result = assertIs<LyricsTranslation.Result.Translated>(LyricsTranslation.translate(UUID.randomUUID().toString(), source.map { LyricLine(0, it) }, "th"))
        assertEquals(target, result.lines.map { it.text })
        assertEquals(5, sent.size, "Two anchors, one batch, and only two echoed French members")
        assertTrue(sent.all { it.second == "auto" })
        assertEquals(0, sent.count { it.first == "We stay together" || it.first == "Love stays with me" }, "Usable English batch members need no standalone retry")
    } }

    private suspend fun isolated(block: suspend () -> Unit) {
        val transport = LyricsTranslation.transport
        val gate = LyricsTranslation.providerRequests
        val cache = LyricsTranslation.cacheDir
        try { LyricsTranslation.providerRequests = LyricsTranslationRateLimit(); LyricsTranslation.cacheDir = null; block() }
        finally { LyricsTranslation.transport = transport; LyricsTranslation.providerRequests = gate; LyricsTranslation.cacheDir = cache }
    }

    private fun response(text: String, source: String, pronunciation: String? = null) = buildJsonArray {
        add(buildJsonArray { add(buildJsonArray { add(text); add(JsonNull); add(JsonNull); add(pronunciation?.let(::JsonPrimitive) ?: JsonNull) }) })
        add(JsonNull); add(source); add(JsonNull); add(JsonNull); add(JsonNull); add(1.0)
    }.toString()

    @Test fun batchedPronunciationMetadataCannotBeCachedAsSemanticThai() = runBlocking { isolated {
        val originals = (0..7).map { "Je reste avec toi $it" }
        val expected = originals.mapIndexed { i, _ -> "ฉันยังอยู่กับคุณ $i" }
        val requests = AtomicInteger()
        LyricsTranslation.transport = { request ->
            requests.incrementAndGet()
            val form = request.body as FormBody
            val text = (0 until form.size).first { form.name(it) == "q" }.let(form::value)
            if ('\uE000' in text) {
                val phonetic = originals.indices.reversed().fold(text) { value, i -> value.replace(originals[i], "เฌอ เรสต์ อาแว็ก ตัว $i") }
                response(phonetic, "fr", phonetic)
            } else response(expected[originals.indexOf(text)], "fr")
        }
        val lines = originals.mapIndexed { i, s -> LyricLine(i * 100L, s) }
        val track = UUID.randomUUID().toString()
        val result = assertIs<LyricsTranslation.Result.Translated>(LyricsTranslation.translate(track, lines, "th"))
        assertEquals(expected, result.lines.map { it.text })
        assertEquals(9, requests.get())
        assertTrue(assertIs<LyricsTranslation.Result.Translated>(LyricsTranslation.translate(track, lines, "th")).fromCache)
    } }

    private fun rejected(request: Request, retryAfter: String = "120") = readLyricsTranslationResponse(
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(429).message("Too Many Requests")
            .header("Retry-After", retryAfter).body("provider rejection must not be treated as lyrics".toResponseBody()).build())

    @Test fun mockHttp429SharesCooldownServesOfflineCacheAndRecoversWithoutClearingIt() = runBlocking { isolated {
        var now = 0L
        LyricsTranslation.providerRequests = LyricsTranslationRateLimit({ now }, { 0.0 })
        val cold = listOf(LyricLine(0, "The moon shines over the river"))
        val warm = listOf(LyricLine(1, "Je m'allonge sur le tapis"))
        val warmTrack = UUID.randomUUID().toString()
        val directory = Files.createTempDirectory("bitchord-rate-limit-cache-").toFile()
        LyricsTranslation.cacheDir = directory
        try {
            val calls = AtomicInteger()
            LyricsTranslation.transport = { calls.incrementAndGet(); response("ฉันนอนลงบนพรม", "fr") }
            assertFalse(assertIs<LyricsTranslation.Result.Translated>(LyricsTranslation.translate(warmTrack, warm, "th")).fromCache)
            val files = directory.walkTopDown().filter { it.isFile }.associate { it.name to it.readBytes().toList() }
            LyricsTranslation.transport = { request -> calls.incrementAndGet(); rejected(request) }
            val first = assertIs<LyricsTranslation.Result.RateLimited>(LyricsTranslation.translate(UUID.randomUUID().toString(), cold, "th-TH"))
            assertEquals(120_000L, first.retryAfterMillis)
            coroutineScope {
                (0..9).map { i -> async { LyricsTranslation.translate("rejection-$i-${UUID.randomUUID()}", cold, "th") } }
                    .awaitAll().forEach { assertIs<LyricsTranslation.Result.RateLimited>(it) }
            }
            assertEquals(2, calls.get(), "One successful cache fill and one rejection only")
            assertIs<LyricsTranslation.RomanizationResult.RateLimited>(LyricsTranslation.romanize(UUID.randomUUID().toString(), listOf(LyricLine(0, "君が好き")), "th"))
            assertEquals(2, calls.get(), "Romanization shares the same provider circuit")
            assertTrue(assertIs<LyricsTranslation.Result.Translated>(LyricsTranslation.translate(warmTrack, warm, "th")).fromCache)
            assertEquals(files, directory.walkTopDown().filter { it.isFile }.associate { it.name to it.readBytes().toList() })
            now = 119_999
            assertIs<LyricsTranslation.Result.RateLimited>(LyricsTranslation.translate(UUID.randomUUID().toString(), cold, "th"))
            assertEquals(2, calls.get())
            now = 120_000
            LyricsTranslation.transport = { calls.incrementAndGet(); response("แสงจันทร์ส่องเหนือแม่น้ำ", "en") }
            val recovered = assertIs<LyricsTranslation.Result.Translated>(LyricsTranslation.translate(UUID.randomUUID().toString(), cold, "th"))
            assertFalse(recovered.fromCache); assertEquals("แสงจันทร์ส่องเหนือแม่น้ำ", recovered.lines.single().text)
            assertEquals(3, calls.get())
        } finally { directory.deleteRecursively() }
    } }

    @Test fun persistedTranslationStillLoadsFromDiskWhileProviderIsRateLimited() = runBlocking { isolated {
        val directory = Files.createTempDirectory("bitchord-offline-disk-").toFile()
        val originalSink = DebugLog.sink
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val original = listOf(LyricLine(100, "Je m'allonge sur le tapis"))
        val track = UUID.randomUUID().toString()
        try {
            LyricsTranslation.cacheDir = directory
            LyricsTranslation.transport = { response("ฉันนอนลงบนพรม", "fr") }
            assertFalse(assertIs<LyricsTranslation.Result.Translated>(LyricsTranslation.translate(track, original, "th")).fromCache)
            val persisted = directory.walkTopDown().filter { it.isFile }.single().readBytes().toList()
            // Evict through the public API: no reflection or forced cache deletion.
            LyricsTranslation.cacheDir = null
            repeat(13) { LyricsTranslation.translate(UUID.randomUUID().toString(), listOf(LyricLine(0, "Other source line $it")), "th") }
            LyricsTranslation.cacheDir = directory
            LyricsTranslation.transport = { rejected(it) }
            assertIs<LyricsTranslation.Result.RateLimited>(LyricsTranslation.translate(UUID.randomUUID().toString(), listOf(LyricLine(0, "New uncached source")), "th"))
            DebugLog.sink = DebugLog.Sink { _, tag, message, _ -> if (tag == "LyricsTranslation") events += message }
            LyricsTranslation.transport = { error("Cached disk lookup must not contact the provider") }
            val disk = assertIs<LyricsTranslation.Result.Translated>(LyricsTranslation.translate(track, original, "th"))
            assertTrue(disk.fromCache); assertEquals("ฉันนอนลงบนพรม", disk.lines.single().text)
            assertTrue(events.any { "cache=disk" in it })
            assertEquals(persisted, directory.walkTopDown().filter { it.isFile }.single().readBytes().toList())
        } finally { DebugLog.sink = originalSink; directory.deleteRecursively() }
    } }

    @Test fun largeEnglishFrenchJapaneseAndMixedSongsKeepEveryMappedLineAndUseFewRequests() = runBlocking { isolated {
        for (languages in listOf(listOf("en"), listOf("fr"), listOf("ja"), listOf("en", "fr"))) {
            val fixtures = (0 until 40).map { index ->
                when (languages[index % languages.size]) {
                    "fr" -> "La lumière du matin traverse la fenêtre $index"
                    "ja" -> "朝の光が窓から差し込む $index"
                    else -> "The morning light shines through window $index"
                } to "แสงยามเช้าส่องผ่านหน้าต่าง $index"
            }
            val calls = AtomicInteger()
            LyricsTranslation.transport = { request ->
                val body = request.body as FormBody
                fun value(key: String) = (0 until body.size).first { body.name(it) == key }.let(body::value)
                assertEquals("th", value("tl")); assertEquals("auto", value("sl")); assertEquals("t", value("dt"))
                calls.incrementAndGet()
                val input = value("q")
                val source = if (input.startsWith("La ")) "fr" else if (input.startsWith("朝")) "ja" else "en"
                response(fixtures.asReversed().fold(input) { value, (from, to) -> value.replace(from, to) }, source)
            }
            val input = fixtures.mapIndexed { i, pair -> LyricLine(i * 100L, pair.first, alignment = if (i % 2 == 0) LyricAlignment.Start else LyricAlignment.End) }
            val track = UUID.randomUUID().toString()
            val output = assertIs<LyricsTranslation.Result.Translated>(LyricsTranslation.translate(track, input, "th"))
            assertFalse(output.fromCache); assertEquals(fixtures.map { it.second }, output.lines.map { it.text })
            assertEquals(input.map { it.timeMs }, output.lines.map { it.timeMs })
            assertEquals(input.map { it.alignment }, output.lines.map { it.alignment })
            assertTrue(calls.get() <= 3, "languages=$languages calls=${calls.get()}")
            println("translation_requests languages=$languages cold_unique_lines=40 requests=${calls.get()}")
            LyricsTranslation.transport = { throw IOException("offline") }
            assertTrue(assertIs<LyricsTranslation.Result.Translated>(LyricsTranslation.translate(track, input, "th")).fromCache)
        }
    } }

    @Test fun foreignEchoNeverBecomesSameLanguageAndBadBatchMarkersNeverShiftTranslations() = runBlocking { isolated {
        val originals = (0..8).map { "Je reste avec toi $it" }
        val lines = originals.mapIndexed { i, s -> LyricLine(i * 100L, s) }
        LyricsTranslation.transport = { request ->
            val form = request.body as FormBody
            val text = (0 until form.size).first { form.name(it) == "q" }.let(form::value)
            response(text, "th")
        }
        assertIs<LyricsTranslation.Result.Unavailable>(LyricsTranslation.translate(UUID.randomUUID().toString(), lines, "th"))
        LyricsTranslation.transport = { request ->
            val form = request.body as FormBody
            val text = (0 until form.size).first { form.name(it) == "q" }.let(form::value)
            if ('\uE000' in text) response("แปลแล้ว\uE0009999\uE001แปลแล้ว", "fr")
            else response("แปลบรรทัด ${text.last()}", "fr")
        }
        val partial = assertIs<LyricsTranslation.Result.Translated>(LyricsTranslation.translate(UUID.randomUUID().toString(), lines, "th"))
        assertEquals("แปลบรรทัด 0", partial.lines.first().text)
        assertEquals("แปลบรรทัด 8", partial.lines.last().text)
        assertEquals(originals.subList(1, 8), partial.lines.subList(1, 8).map { it.text })
    } }
}
