package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real adapter failures must reach the provider gate without losing later playable copies. */
class DesktopSourceFailureIntegrationTest {
    @Test fun cachedSearchUrlCannotHideProviderRefusalOrOutage() = runBlocking {
        for (status in listOf(401, 403, 503)) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val base = "http://127.0.0.1:${server.address.port}"
            val first = DesktopSourceConfig("cached-refusal-$status", DesktopSourceKind.ADDON, baseUrl = "$base/first")
            val second = DesktopSourceConfig("cached-fallback-$status", DesktopSourceKind.ADDON, baseUrl = "$base/second")
            val refusedCalls = AtomicInteger()
            for (prefix in listOf("first", "second")) {
                server.json("/$prefix/manifest.json", """{"id":"$prefix","resources":["search","stream"]}""")
                server.json("/$prefix/search", """{"tracks":[{"id":"copy","title":"Exact Song","artist":"Exact Artist",
                    "duration":120,"format":"flac","streamURL":"$base/$prefix/cached.flac"}]}""")
            }
            server.createContext("/first/stream/copy") { exchange ->
                refusedCalls.incrementAndGet()
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            server.json("/second/stream/copy", """{"url":"$base/second/authorized.flac","format":"flac"}""")
            server.start()
            try {
                repeat(2) {
                    val stream = DesktopSourceRegistry.resolveConfigured(song("Exact Song"), "LOSSLESS", listOf(first, second)).getOrThrow()
                    assertEquals("$base/second/authorized.flac", stream.url,
                        "HTTP $status must retire the provider rather than use a URL cached before the refusal")
                }
                assertEquals(1, refusedCalls.get(), "The unavailable provider must be bypassed on the next resolution")
            } finally {
                for (config in listOf(first, second)) {
                    DesktopSourceRegistry.recordAvailability(config, available = true)
                    DesktopAddonSource.forget(config.id)
                }
                server.stop(0)
            }
        }
    }

    @Test fun ordinaryMissingStreamCanStillUseTheSearchRowsOwnUrl() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        val config = DesktopSourceConfig("cached-ordinary-miss", DesktopSourceKind.ADDON, baseUrl = base)
        server.json("/manifest.json", """{"id":"miss","resources":["search","stream"]}""")
        server.json("/search", """{"tracks":[{"id":"copy","title":"Exact Song","artist":"Exact Artist",
            "duration":120,"format":"flac","streamURL":"$base/cached.flac"}]}""")
        server.createContext("/stream/copy") { exchange -> exchange.sendResponseHeaders(404, -1); exchange.close() }
        server.start()
        try {
            val stream = DesktopSourceRegistry.resolveConfigured(song("Exact Song"), "LOSSLESS", listOf(config)).getOrThrow()
            assertEquals("$base/cached.flac", stream.url)
        } finally {
            DesktopSourceRegistry.recordAvailability(config, available = true)
            DesktopAddonSource.forget(config.id)
            server.stop(0)
        }
    }

    @Test fun moduleIndexAuthFailureStopsFurtherRequests() = runBlocking {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val config = DesktopSourceConfig("index-auth", DesktopSourceKind.CUSTOM_MODULE,
            baseUrl = "http://127.0.0.1:${server.address.port}/index.json")
        server.createContext("/index.json") { exchange ->
            calls.incrementAndGet()
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.start()
        try {
            repeat(2) { DesktopSourceRegistry.resolveConfigured(song("Track $it"), "LOSSLESS", listOf(config)) }
            assertEquals(1, calls.get(), "Index authentication failure must reach the provider gate")
        } finally {
            DesktopSourceRegistry.recordAvailability(config, available = true)
            DesktopModuleSource.reload()
            server.stop(0)
        }
    }

    @Test fun addonOutageSuppressesRequestsUntilRecovery() = runBlocking {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val config = DesktopSourceConfig("outage-gate", DesktopSourceKind.ADDON,
            baseUrl = "http://127.0.0.1:" + server.address.port)
        server.json("/manifest.json", """{"id":"fixture","name":"Fixture","resources":["search","stream"]}""")
        server.createContext("/search") { exchange ->
            calls.incrementAndGet()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        try {
            DesktopSourceRegistry.resolveConfigured(song("First"), "LOSSLESS", listOf(config))
            assertEquals(1, calls.get(), "503 must stop query variants")
            DesktopSourceRegistry.resolveConfigured(song("Second"), "LOSSLESS", listOf(config))
            assertEquals(1, calls.get(), "503 must block the next track")
            DesktopSourceRegistry.recordAvailability(config, available = true)
            DesktopAddonSource.clearCompletedTrackCalls()
            DesktopSourceRegistry.resolveConfigured(song("Recovered"), "LOSSLESS", listOf(config))
            assertEquals(2, calls.get(), "An explicit recovery must admit another request")
        } finally {
            DesktopSourceRegistry.recordAvailability(config, available = true)
            DesktopAddonSource.forget(config.id)
            server.stop(0)
        }
    }

    @Test fun moduleFloorSurvivesLaterMissingStream() = runBlocking {
        withModule("""
            function getTrackStreamUrl(id) {
                return id === 'first' ? {streamUrl:'https://example.invalid/floor.m4a',track:{mimeType:'audio/mp4',bitrate:320}} : {};
            }
        """) { config ->
            assertEquals("https://example.invalid/floor.m4a",
                DesktopSourceRegistry.resolveConfigured(song("Exact Song"), "LOSSLESS", listOf(config)).getOrNull()?.url)
        }
    }

    @Test fun allModuleCopiesMissingProducesOrdinaryMiss() = runBlocking {
        withModule("function getTrackStreamUrl(id) { return {}; }") { config ->
            assertTrue(DesktopSourceRegistry.resolveConfigured(song("Exact Song"), "LOSSLESS", listOf(config)).isFailure)
            val second = DesktopSourceRegistry.resolveConfigured(song("Exact Song"), "LOSSLESS", listOf(config))
            assertTrue(second.isFailure)
            assertEquals(2, DesktopModuleSource.matches(config, song("Exact Song")).size)
        }
    }

    private suspend fun withModule(streamFunction: String, block: suspend (DesktopSourceConfig) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:" + server.address.port
        val config = DesktopSourceConfig("module-floor", DesktopSourceKind.CUSTOM_MODULE, baseUrl = "$base/index.json")
        server.json("/index.json", """{"category:music":[{"id":"fixture","name":"Fixture","download":"module.js"}]}""")
        server.json("/module.js", """
            function searchTracks() { return {tracks:[
                {id:'first',title:'Exact Song',artist:'Exact Artist',duration:120,audioQuality:'LOSSLESS'},
                {id:'second',title:'Exact Song',artist:'Exact Artist',duration:120,audioQuality:'LOSSLESS'}]}; }
            $streamFunction
        """.trimIndent())
        server.start()
        try { block(config) } finally {
            DesktopSourceRegistry.recordAvailability(config, available = true)
            DesktopModuleSource.reload()
            server.stop(0)
        }
    }

    @Test fun addonAuthFailureSuppressesSubsequentTrackRequests() = runBlocking {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        val config = DesktopSourceConfig("review-auth-gate", DesktopSourceKind.ADDON,
            baseUrl = "http://127.0.0.1:" + server.address.port)
        server.json("/manifest.json", """{"id":"review","name":"Review","resources":["search","stream"]}""")
        server.createContext("/search") { exchange ->
            calls.incrementAndGet()
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        try {
            val first = DesktopSourceRegistry.resolveConfigured(song("First Failure"), "LOSSLESS", listOf(config))
            assertTrue(first.isFailure)
            val afterFirst = calls.get()
            assertTrue(afterFirst > 0, "First real adapter request must reach HTTP fixture")
            DesktopSourceRegistry.resolveConfigured(song("Second Failure"), "LOSSLESS", listOf(config))
            assertEquals(afterFirst, calls.get(), "401 must block subsequent track requests before network access")
        } finally {
            DesktopSourceRegistry.recordAvailability(config, available = true)
            DesktopAddonSource.forget(config.id)
            server.stop(0)
        }
    }

    @Test fun moduleCandidateFailureStillTriesLaterExactCopy() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        val base = "http://127.0.0.1:" + server.address.port
        val config = DesktopSourceConfig("review-candidates", DesktopSourceKind.CUSTOM_MODULE, baseUrl = base + "/index.json")
        server.json("/index.json", """{"category:music":[{"id":"fixture","name":"Fixture","download":"module.js"}]}""")
        server.json("/module.js", """
            function searchTracks(query, limit, context) {
                return {tracks:[
                    {id:'bad',title:'Exact Song',artist:'Exact Artist',duration:120,audioQuality:'LOSSLESS'},
                    {id:'good',title:'Exact Song',artist:'Exact Artist',duration:120,audioQuality:'LOSSLESS'}
                ]};
            }
            function getTrackStreamUrl(id, quality, context) {
                if (id === 'bad') return {};
                return {streamUrl:'https://example.invalid/good.flac',track:{mimeType:'audio/flac',audioQuality:'LOSSLESS'}};
            }
        """.trimIndent())
        try {
            val target = song("Exact Song")
            val copies = DesktopModuleSource.matches(config, target)
            assertEquals(2, copies.size, "Fixture must produce two strict matches")
            assertTrue(DesktopModuleSource.stream(config, copies[0], "LOSSLESS").isFailure)
            assertTrue(DesktopModuleSource.stream(config, copies[1], "LOSSLESS").isSuccess)
            val result = DesktopSourceRegistry.resolveConfigured(target, "LOSSLESS", listOf(config))
            assertEquals("https://example.invalid/good.flac", result.getOrNull()?.url,
                "A candidate-specific missing URL must not discard a later exact playable copy")
        } finally {
            DesktopSourceRegistry.recordAvailability(config, available = true)
            DesktopModuleSource.reload()
            server.stop(0)
        }
    }

    private fun song(title: String) = Song("fixture-origin", title, "Exact Artist", "", durationText = "2:00")
    private fun HttpServer.json(path: String, body: String) {
        createContext(path) { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
