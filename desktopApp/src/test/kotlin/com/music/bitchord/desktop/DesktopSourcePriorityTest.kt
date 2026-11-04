package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class DesktopSourcePriorityTest {
    @Test
    fun `migration retains opaque data credentials disabled states and stable repaired ids`() {
        val raw = """[{"id":"saved","kind":"ADDON","baseUrl":"https://alice:Secret@example.test/private?key=Case","enabled":false,"allowDownloads":false,"future":{"value":7}},{"id":"saved","kind":"MODULE","baseUrl":"https://example.test/index"},{"kind":"YOUTUBE","enabled":false},{"id":"unknown","kind":"FUTURE","secret":"keep"}]"""
        val decoded = DesktopSourceStorage.decode(raw)
        assertEquals(3, decoded.configs.size)
        assertEquals(3, decoded.configs.map { it.id }.distinct().size)
        assertEquals(decoded, DesktopSourceStorage.decode(raw))
        val normalized = DesktopSourceStorage.normalize(decoded.configs, false)
        assertEquals(decoded.configs.first(), normalized.first())
        assertEquals(DesktopSourceKind.CUSTOM_MODULE, normalized[1].kind)
        assertFalse(normalized.first { it.kind == DesktopSourceKind.YOUTUBE }.enabled)
        val encoded = Json.parseToJsonElement(DesktopSourceStorage.encode(normalized, decoded)).jsonArray
        assertEquals(Json.parseToJsonElement("""{"value":7}"""), encoded.first().jsonObject["future"])
        assertEquals(Json.parseToJsonElement(raw).jsonArray.last(), encoded.last())
    }

    @Test
    fun `first-run builtins have expected order and consent defaults while saved order survives`() {
        val initial = DesktopSourceStorage.normalize(emptyList(), false)
        assertEquals(listOf(DesktopSourceKind.JIOSAAVN, DesktopSourceKind.SPOTIFY, DesktopSourceKind.YOUTUBE), initial.map { it.kind })
        assertFalse(initial[0].enabled)
        assertFalse(initial[1].enabled)
        val saved = initial.reversed().map { it.copy(enabled = false) }
        assertEquals(saved, DesktopSourceStorage.normalize(saved, true))
        assertEquals(listOf(initial[1], initial[0], initial[2]), moveUserSource(initial, initial[1].id, -1))
    }

    @Test
    fun `foreground waits for higher priority and never queries lower after success`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val configs = listOf(DesktopSourceConfig("higher", DesktopSourceKind.ADDON, baseUrl = "https://example.test"), DesktopSourceConfig("yt", DesktopSourceKind.YOUTUBE))
        val resolving = async {
            walkDesktopSources(configs, DesktopSourceAvailability()) { config ->
                calls += config.id
                if (config.id == "higher") { started.complete(Unit); release.await() }
                config.id
            }
        }
        started.await()
        assertEquals(listOf("higher"), calls)
        release.complete(Unit)
        assertEquals("higher", resolving.await())
        assertEquals(listOf("higher"), calls)
    }

    @Test
    fun `disabled unsupported and cooling sources are not contacted and miss is not an outage`() = runBlocking {
        var now = 0L
        val gate = DesktopSourceAvailability { now }
        val jio = DesktopSourceConfig("jio", DesktopSourceKind.JIOSAAVN, enabled = true)
        val youtube = DesktopSourceConfig("yt", DesktopSourceKind.YOUTUBE)
        val spotify = DesktopSourceConfig("spotify", DesktopSourceKind.SPOTIFY, enabled = true)
        val calls = mutableListOf<String>()
        repeat(2) {
            assertEquals("yt", walkDesktopSources(listOf(spotify, jio, youtube), gate) { calls += it.id; if (it == youtube) "yt" else null })
        }
        assertEquals(listOf("jio", "yt", "jio", "yt"), calls)
        walkDesktopSources<String>(listOf(jio), gate) { throw IOException("offline") }
        assertFalse(gate.canAttempt(jio))
        assertTrue(gate.canAttempt(jio.copy(label = "changed")))
        gate.unavailable(jio)
        now = 30_000
        assertTrue(gate.canAttempt(jio))
        assertFalse(gate.canAttempt(youtube.copy(enabled = false)))
        gate.unavailable(jio, rejected = true)
        now += 100_000
        assertFalse(gate.canAttempt(jio))
        gate.recovered(jio)
        assertTrue(gate.canAttempt(jio))
        walkDesktopSources<String>(listOf(jio), gate) { throw DesktopAddonException("HTTP 401") }
        assertFalse(gate.canAttempt(jio))
        now += 100_000
        assertFalse(gate.canAttempt(jio))
        gate.recovered(jio)
        walkDesktopSources<String>(listOf(jio), gate) { throw DesktopAddonNotFound() }
        assertTrue(gate.canAttempt(jio))
    }
    @Test
    fun `builtin playback priority follows the saved order`() {
        val youtube = DesktopSourceConfig("yt", DesktopSourceKind.YOUTUBE, enabled = false)
        val jio = DesktopSourceConfig("jio", DesktopSourceKind.JIOSAAVN, enabled = false)
        assertEquals(listOf(youtube, jio), listOf(youtube, jio).inSourceOrder())
    }

    @Test
    fun `custom sources stay ahead of builtins without regrouping their kinds`() {
        val youtube = DesktopSourceConfig("yt", DesktopSourceKind.YOUTUBE)
        val module = DesktopSourceConfig("module", DesktopSourceKind.CUSTOM_MODULE, baseUrl = "https://example.test/index")
        val addon = DesktopSourceConfig("addon", DesktopSourceKind.ADDON, baseUrl = "https://example.test/addon")
        val jio = DesktopSourceConfig("jio", DesktopSourceKind.JIOSAAVN)
        assertEquals(listOf(module, addon, youtube, jio), listOf(youtube, module, addon, jio).inSourceOrder())
    }

    @Test
    fun `credential-bearing source urls remain distinct`() {
        assertNotEquals(
            canonicalSourceUrl("https://alice:one@example.test/addon?token=A"),
            canonicalSourceUrl("https://bob:two@example.test/addon?token=A"),
        )
        assertEquals(
            "https://alice:one@example.test/addon?token=A",
            canonicalSourceUrl("HTTPS://alice:one@EXAMPLE.test:443/addon/?token=A"),
        )
    }
}
