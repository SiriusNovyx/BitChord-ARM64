package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSourceOwnershipTest {
    @Test
    fun `picked addon fallback never includes youtube even if it is first builtin`() {
        val addon = DesktopSourceConfig("addon", DesktopSourceKind.ADDON, baseUrl = "https://example.test")
        val youtube = DesktopSourceConfig("yt", DesktopSourceKind.YOUTUBE)
        val jio = DesktopSourceConfig("jio", DesktopSourceKind.JIOSAAVN, enabled = true)
        assertEquals(listOf(jio), ownedSourceFallbackConfigs(listOf(addon, youtube, jio), addon.id, true))
        assertEquals(listOf(youtube, jio), ownedSourceFallbackConfigs(listOf(youtube, jio), "missing-module", false))
    }

    @Test
    fun `cooling or rejected picked addon is skipped and eligible addon fallback still serves`() = runBlocking {
        val calls = AtomicInteger()
        val blockedCalls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val picked = DesktopSourceConfig("source-owned-blocked", DesktopSourceKind.ADDON, label = "Picked source", baseUrl = "$base/blocked")
        val fallback = DesktopSourceConfig("source-owned-fallback", DesktopSourceKind.ADDON, baseUrl = "$base/fallback")
        server.createContext("/blocked") { exchange ->
            blockedCalls.incrementAndGet()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.json("/fallback/manifest.json", """{"id":"fallback","name":"Fallback","resources":["search","stream"]}""")
        server.json("/fallback/search", """{"tracks":[{"id":"copy","title":"Exact Song","artist":"Exact Artist","duration":120.0,"audioQuality":"LOSSLESS"}]}""", calls)
        server.json("/fallback/stream/copy", """{"url":"https://example.invalid/audio.flac","codec":"flac"}""")
        val song = Song(DesktopAddonSource.trackKey(picked.id, "song"), "Exact Song", "Exact Artist", "", durationText = "2:00")
        try {
            for (rejected in listOf(false, true)) {
                DesktopSourceRegistry.recordAvailability(picked, available = false, rejected = rejected)
                val stream = DesktopSourceRegistry.resolveConfigured(song, "LOSSLESS", listOf(picked, fallback)).getOrThrow()
                assertEquals(fallback.id, stream.sourceId)
            }
            assertEquals(0, blockedCalls.get())
            assertTrue(calls.get() > 0)
        } finally {
            DesktopSourceRegistry.recordAvailability(picked, available = true)
            DesktopAddonSource.forget(picked.id)
            DesktopAddonSource.forget(fallback.id)
            server.stop(0)
        }
    }

    @Test
    fun `unavailable picked addon is not mistaken for a download policy holdback`() = runBlocking {
        val picked = DesktopSourceConfig("source-owned-no-copy", DesktopSourceKind.ADDON, label = "Picked unavailable source", baseUrl = "http://127.0.0.1:1")
        val youtube = DesktopSourceConfig("yt-disabled-for-offline-test", DesktopSourceKind.YOUTUBE, enabled = false)
        val song = Song(DesktopAddonSource.trackKey(picked.id, "song"), "Exact Song", "Exact Artist", "", durationText = "2:00")
        DesktopSourceRegistry.recordAvailability(picked, available = false, rejected = true)
        try {
            val failure = DesktopSourceRegistry.resolveConfigured(song, "LOSSLESS", listOf(picked, youtube)).exceptionOrNull()
            assertEquals("Picked unavailable source did not return an audio stream", failure?.message)
        } finally {
            DesktopSourceRegistry.recordAvailability(picked, available = true)
        }
    }

    private fun HttpServer.json(path: String, body: String, calls: AtomicInteger? = null) {
        createContext(path) { exchange ->
            calls?.incrementAndGet()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
