package com.music.bitchord

import com.music.bitchord.data.Http
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.download.Downloader
import com.music.bitchord.download.Downloads
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DownloadSourceRoutingTest {
    private val first = MockWebServer()
    private val second = MockWebServer()
    private val externalAttempts = AtomicInteger()
    private val oldConfigs = SourceRegistry.configs.value
    private val instanceField = SourceRegistry::class.java.getDeclaredField("instances").apply { isAccessible = true }
    private val oldInstances = instanceField.get(SourceRegistry)
    private val interceptorsField = OkHttpClient::class.java.getDeclaredField("interceptors").apply { isAccessible = true }
    private lateinit var oldInterceptors: List<Interceptor>
    private val oldExport = AppSettings.exportDownloads.value

    @Before fun setUp() {
        first.start()
        second.start()
        @Suppress("UNCHECKED_CAST")
        oldInterceptors = interceptorsField.get(Http.client) as List<Interceptor>
        val guard = Interceptor { chain ->
            val host = chain.request().url.host
            if (host != "localhost" && host != "127.0.0.1") {
                externalAttempts.incrementAndGet()
                throw IOException("External network denied by download routing fixture")
            }
            chain.proceed(chain.request())
        }
        interceptorsField.set(Http.client, listOf(guard) + oldInterceptors)
        AppSettings.exportDownloads.value = false
    }

    @After fun tearDown() {
        SourceRegistry.configs.value.forEach { SourceRegistry.recordHealth(it.id, SourceHealth.Ok()) }
        SourceRegistry.configs.value = oldConfigs
        instanceField.set(SourceRegistry, oldInstances)
        Http.client.dispatcher.cancelAll()
        interceptorsField.set(Http.client, oldInterceptors)
        AppSettings.exportDownloads.value = oldExport
        first.shutdown()
        second.shutdown()
    }

    @Test fun `download uses earlier provider and does not start a lower request`() = runBlocking {
        val higher = addon(first, "higher", bitrate = 320, delayMs = 150)
        val lower = addon(second, "lower", bitrate = 384)
        configure(listOf(higher, lower))
        assertNotNull(route(DownloadQuality.HIGH))
        assertTrue(first.requestCount > 0)
        assertEquals("A lower provider must wait for an earlier usable one", 0, second.requestCount)
        assertEquals(0, externalAttempts.get())
    }

    @Test fun `lossless download keeps searching after an earlier lossy floor`() = runBlocking {
        val lossy = addon(first, "lossy", 320)
        val lossless = addon(second, "lossless", 1411, codec = "flac")
        configure(listOf(lossy, lossless))
        val result = route(DownloadQuality.LOSSLESS)
        val extension = result.javaClass.getDeclaredField("extension").apply { isAccessible = true }.get(result)
        assertEquals("An inadequate early rendition must not defeat the requested lossless format", "flac", extension)
        assertTrue(first.requestCount > 0)
        assertTrue(second.requestCount > 0)
        assertEquals(0, externalAttempts.get())
    }

    @Test fun `high download declines a lossless file and keeps the requested file size tier`() = runBlocking {
        val lossless = addon(first, "over-budget", 1411, codec = "flac")
        val lossy = addon(second, "within-budget", 320)
        configure(listOf(lossless, lossy))
        val result = route(DownloadQuality.HIGH)
        val extension = result.javaClass.getDeclaredField("extension").apply { isAccessible = true }.get(result)
        assertEquals("m4a", extension)
        assertTrue(second.requestCount > 0)
        assertEquals(0, externalAttempts.get())
    }

    @Test fun `failed youtube falls through to eligible source below it`() = runBlocking {
        configure(emptyList(), youtubeFirst = true)
        var searched = false
        val config = SourceConfig("after-youtube", SourceKind.JIOSAAVN, enabled = true)
        first.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = fileResponse("m4a")
        }
        val lower = object : com.music.bitchord.data.sources.MusicSource {
            override val configId = config.id
            override val kind = config.kind
            override val displayName = "Builtin fixture"
            override suspend fun health() = SourceHealth.Ok()
            override suspend fun search(query: String, limit: Int, waitForAll: Boolean,
                request: com.music.bitchord.data.sources.StreamRequest?): List<Song> {
                searched = true
                return listOf(Song("builtin-copy", "Exact Song", "Exact Artist", null, durationText = "2:00"))
            }
            override suspend fun stream(trackId: String, request: com.music.bitchord.data.sources.StreamRequest) =
                com.music.bitchord.data.sources.SourceStream(first.url("/builtin.m4a").toString(),
                    com.music.bitchord.data.sources.StreamFormat("mp4", kbps = 320))
        }
        SourceRegistry.configs.value += config
        @Suppress("UNCHECKED_CAST")
        val instances = instanceField.get(SourceRegistry) as Map<String, com.music.bitchord.data.sources.MusicSource>
        instanceField.set(SourceRegistry, instances + (config.id to lower))
        assertNotNull(route(DownloadQuality.HIGH))
        assertTrue("YouTube's failed attempt must reach the lower strict match", searched)
    }

    @Test fun `high download retains lossless reserve when no smaller source is available`() = runBlocking {
        configure(listOf(addon(first, "only-lossless", 1411, codec = "flac")))
        val result = route(DownloadQuality.HIGH)
        val extension = result.javaClass.getDeclaredField("extension").apply { isAccessible = true }.get(result)
        assertEquals("flac", extension)
        assertEquals(0, second.requestCount)
        assertEquals(0, externalAttempts.get())
    }

    @Test fun `youtube off blocks initial capped download before network`() = runBlocking {
        configure(emptyList())
        val failure = runCatching { route(DownloadQuality.STANDARD) }.exceptionOrNull()
        assertNotNull("No eligible download source must fail", failure)
        assertTrue("Disabled source must be rejected before invoking the client runtime: ${failure?.message}",
            failure?.message.orEmpty().let { it.contains("disabled") || it.contains("No enabled") })
        assertEquals("Disabled YouTube must not reach its resolver", 0, externalAttempts.get())
    }

    @Test fun `youtube turned off during transfer blocks retry resolution`() = runBlocking {
        configure(emptyList(), youtubeFirst = true)
        first.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                SourceRegistry.configs.value = SourceRegistry.configs.value.map { it.copy(enabled = false) }
                return MockResponse().setResponseCode(403)
            }
        }
        val stream = StreamResolver.Stream(first.url("/audio?clen=8").toString(), 128, "audio/mp4")
        val failure = runCatching {
            Downloader.fetch("fixture0001", stream, 128, false, ByteArrayOutputStream()) { _, _ -> }
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue("Disabled retry must be rejected before invoking the client runtime: ${failure?.message}",
            failure?.message.orEmpty().contains("disabled"))
        assertEquals(1, first.requestCount)
        assertEquals("A retry must consult the current enabled state", 0, externalAttempts.get())
    }

    @Test fun `known unavailable first source is bypassed for next strict match`() = runBlocking {
        val higher = addon(first, "unavailable", 320)
        val lower = addon(second, "available", 320)
        configure(listOf(higher, lower))
        SourceRegistry.recordHealth(higher.config.id, SourceHealth.Unreachable("fixture outage"))
        assertNotNull(route(DownloadQuality.HIGH))
        assertEquals(0, first.requestCount)
        assertTrue(second.requestCount > 0)
    }

    private fun configure(addons: List<AddonSource>, youtubeFirst: Boolean = false) {
        val youtube = SourceConfig("download-youtube", SourceKind.YOUTUBE, enabled = youtubeFirst)
        SourceRegistry.configs.value = listOf(youtube) + addons.map { it.config }
        instanceField.set(SourceRegistry, addons.associateBy { it.config.id } +
            (youtube.id to com.music.bitchord.data.sources.YouTubeSource(youtube)))
    }

    private fun addon(server: MockWebServer, id: String, bitrate: Int, delayMs: Long = 0, codec: String? = null): AddonSource {
        val extension = if (codec == "flac") "flac" else "m4a"
        val codecField = codec?.let { ""","codec":"$it"""" }.orEmpty()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.requestUrl?.encodedPath) {
                    "/manifest.json" -> """{"id":"$id","name":"$id","resources":["search","stream"]}"""
                    "/search" -> """{"tracks":[{"id":"copy","title":"Exact Song","artist":"Exact Artist","duration":120,"audioQuality":"HIGH"}]}"""
                    "/stream/copy" -> """{"url":"${server.url("/audio.$extension")}","bitrate":$bitrate$codecField}"""
                    "/audio.$extension" -> return fileResponse(extension)
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body).setBodyDelay(delayMs, TimeUnit.MILLISECONDS)
            }
        }
        return AddonSource(SourceConfig(id, SourceKind.ADDON, baseUrl = server.url("/").toString()))
    }

    private fun fileResponse(extension: String): MockResponse {
        // The current download path probes actual bytes before choosing a route.
        val head = if (extension == "flac") "fLaC" else "\u0000\u0000\u0000\u0018ftypM4A "
        return MockResponse().setBody(head + "\u0000".repeat(512))
    }

    private suspend fun route(quality: DownloadQuality): Any = suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = Downloads::class.java.getDeclaredMethod("routeFor", android.content.Context::class.java,
            Song::class.java, DownloadQuality::class.java, Continuation::class.java).apply { isAccessible = true }
        try {
            method.invoke(Downloads, null, Song("fixture0001", "Exact Song", "Exact Artist", null, durationText = "2:00"),
                quality, continuation)
        } catch (failure: InvocationTargetException) { throw failure.targetException }
    }
}
