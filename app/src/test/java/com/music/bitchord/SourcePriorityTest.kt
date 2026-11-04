package com.music.bitchord

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class SourcePriorityTest {
    @Test
    fun `priority migration preserves explicit disabled youtube and enabled jio`() {
        val youtube = SourceConfig(id = "my-youtube", kind = SourceKind.YOUTUBE, enabled = false)
        val jio = SourceConfig(id = "my-jio", kind = SourceKind.JIOSAAVN, enabled = true)
        val migrated = SourceRegistry.sourcesForInit(listOf(youtube, jio), forceJioSaavnOff = false)
        assertFalse(migrated.single { it.id == youtube.id }.enabled)
        assertTrue(migrated.single { it.id == jio.id }.enabled)
    }

    @Test
    fun `source migration preserves custom credentials and policy`() {
        val addon = SourceConfig(
            id = "my-addon", kind = SourceKind.ADDON,
            baseUrl = "https://alice:secret@example.test/private/Token?key=CaseSensitive",
            enabled = false, allowDownloads = false, checkValidLossless = true,
        )
        assertEquals(addon, SourceRegistry.sourcesForInit(listOf(addon), false).first { it.id == addon.id })
    }

    @Test
    fun `unknown entries opaque fields duplicate and missing ids survive roundtrip`() {
        val raw = """[
            {"id":"saved","kind":"ADDON","baseUrl":"https://alice:Secret@example.test/A?token=Key","enabled":false,"future":{"value":7}},
            {"id":"saved","kind":"CUSTOM_MODULE","baseUrl":"https://example.test/index"},
            {"kind":"YOUTUBE","enabled":false},
            {"id":"future","kind":"FUTURE_PROVIDER","secret":"opaque"}
        ]""".trimIndent()
        val decoded = SourceRegistry.decodeStored(raw)
        assertEquals(3, decoded.size)
        assertEquals(3, decoded.map { it.id }.distinct().size)
        assertEquals("saved", decoded.first().id)
        val encoded = SourceRegistry.encodeStored(SourceRegistry.sourcesForInit(decoded, false))
        val objects = Json.parseToJsonElement(encoded).jsonArray.map { it.jsonObject }
        assertEquals(Json.parseToJsonElement("""{"value":7}"""), objects.first { it["id"].toString() == "\"saved\"" }["future"])
        assertEquals(Json.parseToJsonElement(raw).jsonArray.last(), objects.last())
        assertEquals(decoded.map { it.id }, SourceRegistry.decodeStored(raw).map { it.id })
    }

    @Test
    fun `new builtins default to jio spotify youtube and unavailable spotify is bypassed`() {
        val configs = SourceRegistry.sourcesForInit(emptyList(), false)
        assertEquals(listOf(SourceKind.JIOSAAVN, SourceKind.SPOTIFY, SourceKind.YOUTUBE), configs.map { it.kind })
        assertFalse(configs.first().enabled)
        assertTrue(SourceRegistry.enabledConfigs(configs.map { it.copy(enabled = true) }).none { it.kind == SourceKind.SPOTIFY })
    }

    @Test
    fun `legacy configured module survives migration with identity and credentials`() {
        val legacy = SourceConfig(id = "legacy", kind = SourceKind.MODULE, baseUrl = "https://example.test/private?token=A", enabled = false)
        assertEquals(legacy.copy(kind = SourceKind.CUSTOM_MODULE), SourceRegistry.sourcesForInit(listOf(legacy), false).first())
    }

    @Test
    fun `saved builtin order remains stable after priority migration`() {
        val youtube = SourceConfig(id = "yt", kind = SourceKind.YOUTUBE, enabled = false)
        val jio = SourceConfig(id = "jio", kind = SourceKind.JIOSAAVN, enabled = true)
        val spotify = SourceConfig(id = "spotify", kind = SourceKind.SPOTIFY)
        assertEquals(listOf(youtube, jio, spotify), SourceRegistry.sourcesForInit(listOf(youtube, jio, spotify), false, preserveOrder = true))
    }

    @Test
    fun `known unavailable source is bypassed before search until health recovers`() = runBlocking {
        val calls = mutableListOf<String>()
        val blocked = source("blocked-provider", calls) { true }
        val holder = source("available-provider", calls) { true }
        try {
            SourceRegistry.recordHealth(blocked.configId, SourceHealth.Unreachable("offline"))
            assertEquals(holder.configId, SourceResolver.orderedAcross(listOf(blocked, holder), target, StreamRequest.Best)!!.first.configId)
            assertEquals(listOf(holder.configId), calls)
            SourceRegistry.recordHealth(blocked.configId, SourceHealth.Ok())
            assertEquals(blocked.configId, SourceResolver.orderedAcross(listOf(blocked, holder), target, StreamRequest.Best)!!.first.configId)
        } finally {
            SourceRegistry.recordHealth(blocked.configId, SourceHealth.Ok())
        }
    }

    @Test
    fun `ordered foreground waits for higher source and does not query lower after success`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val higher = source("higher", calls) { started.complete(Unit); release.await(); true }
        val lower = source("lower", calls) { true }
        val resolving = async { SourceResolver.orderedAcross(listOf(higher, lower), target, StreamRequest.Best) }
        started.await()
        assertEquals(listOf("higher"), calls)
        release.complete(Unit)
        assertEquals("higher", resolving.await()!!.first.configId)
        assertEquals(listOf("higher"), calls)
    }

    @Test
    fun `catalogue miss falls through and does not disable the first provider`() = runBlocking {
        val calls = mutableListOf<String>()
        val missing = source("missing", calls) { false }
        val holder = source("holder", calls) { true }
        repeat(2) {
            assertEquals("holder", SourceResolver.orderedAcross(listOf(missing, holder), target, StreamRequest.Best)!!.first.configId)
        }
        assertEquals(listOf("missing", "missing", "holder", "missing", "missing", "holder"), calls)
    }

    private val target = TrackMatcher.Target("Exact Song", "Exact Artist", durationSec = 120)

    private fun source(id: String, calls: MutableList<String>, found: suspend () -> Boolean) = object : MusicSource {
        override val configId = id
        override val kind = SourceKind.JIOSAAVN
        override val displayName = id
        override suspend fun health() = SourceHealth.Ok()
        override suspend fun search(query: String, limit: Int, waitForAll: Boolean, request: StreamRequest?): List<Song> {
            calls += id
            return if (found()) listOf(Song(id, "Exact Song", "Exact Artist", null, durationText = "2:00")) else emptyList()
        }
        override suspend fun stream(trackId: String, request: StreamRequest) =
            SourceStream("https://example.test/$id", StreamFormat("mp4", kbps = 320))
    }
}
