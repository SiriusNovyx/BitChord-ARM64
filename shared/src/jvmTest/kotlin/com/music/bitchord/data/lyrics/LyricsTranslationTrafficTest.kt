package com.music.bitchord.data.lyrics

import com.music.bitchord.data.DebugLog
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.FormBody
import kotlin.test.*

class LyricsTranslationTrafficTest {
    @Test fun validBatchedEnglishBetweenFrenchAnchorsIsNotRetriedAsFrench() = runBlocking {
        val previous = LyricsTranslation.transport
        val originals = listOf("Je reste avec toi", "I love you", "Love stays with me", "Je reviens demain")
        val expected = listOf("ฉันอยู่กับเธอ", "ฉันรักคุณ", "ความรักอยู่กับฉัน", "ฉันจะกลับมาพรุ่งนี้")
        val seen = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        try {
            LyricsTranslation.transport = { request ->
                val body = request.body as FormBody
                fun value(key: String) = (0 until body.size).first { body.name(it) == key }.let(body::value)
                val text = value("q"); val source = value("sl"); seen += text to source
                val translated = if (source == "fr" && text.startsWith("I love")) "ไอ เลิฟ ยู"
                    else originals.indices.reversed().fold(text) { value, i -> value.replace(originals[i], expected[i]) }
                response(translated, if (text.startsWith("Je")) "fr" else "en")
            }
            val result = assertIs<LyricsTranslation.Result.Translated>(LyricsTranslation.translate(UUID.randomUUID().toString(), originals.map { LyricLine(0, it) }, "th"))
            assertEquals(expected, result.lines.map { it.text })
            assertEquals(3, seen.size)
            assertTrue(seen.all { it.second == "auto" })
        } finally { LyricsTranslation.transport = previous }
    }

    private fun response(text: String, source: String = "en") = buildJsonArray {
        add(buildJsonArray { add(buildJsonArray { add(text); add(JsonNull); add(JsonNull); add(JsonNull) }) })
        add(JsonNull); add(source); add(JsonNull); add(JsonNull); add(JsonNull); add(1.0)
    }.toString()

    @Test fun coldEnglishSongUsesBoundedBatchesAndWarmSongDoesNotRequest() = runBlocking {
        val previous = LyricsTranslation.transport
        val cache = LyricsTranslation.cacheDir
        val log = DebugLog.sink
        val messages = mutableListOf<String>()
        val sent = java.util.Collections.synchronizedList(mutableListOf<String>())
        val originals = (0 until 40).map { "The morning light shines through window $it" }
        val outputs = originals.mapIndexed { i, _ -> "แสงยามเช้าส่องผ่านหน้าต่าง $i" }
        val lines = originals.mapIndexed { i, text -> LyricLine(i * 1000L, text) }
        val track = UUID.randomUUID().toString()
        try {
            LyricsTranslation.cacheDir = null
            DebugLog.sink = DebugLog.Sink { _, tag, message, _ -> if (tag == "LyricsTranslation") synchronized(messages) { messages += message } }
            LyricsTranslation.transport = { request ->
                val body = request.body as FormBody
                fun value(key: String) = (0 until body.size).first { body.name(it) == key }.let(body::value)
                assertEquals("th", value("tl")); assertEquals("auto", value("sl"))
                val text = value("q"); sent += text
                response(originals.indices.reversed().fold(text) { result, i -> result.replace(originals[i], outputs[i]) })
            }
            val cold = assertIs<LyricsTranslation.Result.Translated>(LyricsTranslation.translate(track, lines, "th"))
            assertFalse(cold.fromCache)
            assertEquals(outputs, cold.lines.map { it.text })
            assertEquals(lines.map { it.timeMs }, cold.lines.map { it.timeMs })
            assertTrue(sent.size <= 3, "40 short distinct lines must not cause ${sent.size} provider requests")
            val requests = sent.size
            LyricsTranslation.transport = { throw IOException("offline") }
            val warm = assertIs<LyricsTranslation.Result.Translated>(LyricsTranslation.translate(track, lines, "th"))
            assertTrue(warm.fromCache); assertEquals(outputs, warm.lines.map { it.text })
            assertEquals(requests, sent.size)
            assertTrue(messages.any { "cache=memory" in it && "target=th" in it })
            assertTrue(messages.none { message -> originals.any { it in message } || outputs.any { it in message } })
        } finally { LyricsTranslation.transport = previous; LyricsTranslation.cacheDir = cache; DebugLog.sink = log }
    }

    @Test fun providerFailureIsObservableAndDoesNotPoisonFreshRecovery() = runBlocking {
        val previous = LyricsTranslation.transport
        val log = DebugLog.sink
        val messages = mutableListOf<String>()
        val track = UUID.randomUUID().toString()
        val oldGate = LyricsTranslation.providerRequests
        var now = 0L
        LyricsTranslation.providerRequests = LyricsTranslationRateLimit({ now }, { 0.0 })
        val lines = listOf(LyricLine(0, "The sun shines through the window"))
        try {
            DebugLog.sink = DebugLog.Sink { _, tag, message, _ -> if (tag == "LyricsTranslation") synchronized(messages) { messages += message } }
            LyricsTranslation.transport = { throw IOException("offline private failure") }
            assertIs<LyricsTranslation.Result.Unavailable>(LyricsTranslation.translate(track, lines, "th"))
            assertTrue(messages.any { "outcome=unavailable" in it && "reason=io" in it })
            now = 60_000
            LyricsTranslation.transport = { response("แสงแดดส่องผ่านหน้าต่าง") }
            val recovered = assertIs<LyricsTranslation.Result.Translated>(LyricsTranslation.translate(track, lines, "th"))
            assertFalse(recovered.fromCache)
            assertEquals("แสงแดดส่องผ่านหน้าต่าง", recovered.lines.single().text)
            assertTrue(messages.any { "outcome=translated" in it })
            assertTrue(messages.none { "private failure" in it || lines.single().text in it })
        } finally { LyricsTranslation.transport = previous; DebugLog.sink = log; LyricsTranslation.providerRequests = oldGate }
    }
}
