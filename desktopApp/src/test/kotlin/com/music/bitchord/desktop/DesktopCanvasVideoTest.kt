package com.music.bitchord.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.ui.player.CanvasContentMode
import com.music.bitchord.ui.player.CanvasVideoSpec
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesktopCanvasVideoTest {
    private class ClipServer : AutoCloseable {
        val requests = AtomicInteger()
        private val bytes = requireNotNull(javaClass.getResourceAsStream("/canvas/timed.mp4")).use { it.readBytes() }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/clip.mp4") { exchange ->
                requests.incrementAndGet()
                exchange.responseHeaders.add("Content-Type", "video/mp4")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}/clip.mp4?test=${UUID.randomUUID()}"
        override fun close() = server.stop(0)
    }

    private fun spec(
        url: String,
        paused: Boolean,
        capture: Boolean,
        onStill: (ImageBitmap?, Long) -> Unit,
        onResumed: () -> Unit,
        onFrame: (ImageBitmap) -> Unit = {},
        frameCapturePx: Int = 0,
    ) = CanvasVideoSpec(
        canvas = CanvasArtwork(url = url), isPlaying = true, contentMode = CanvasContentMode.CROP,
        alignPortraitTop = false, onAspectRatioChanged = {}, portraitRevealBounds = IntSize.Zero,
        presentationAlpha = { 1f }, onRenderedChanged = {}, onFrameCaptured = onFrame,
        refreshFrameEveryMs = 40L, frameCapturePx = frameCapturePx, onCoverChanged = {},
        bottomFade = 0f, bottomFadeEndPx = null, pausedForTransition = paused,
        resumeDelayMs = 50L, startPositionMs = 750L, revealInstantly = true,
        captureStill = capture, onStillCaptured = onStill, onResumed = onResumed,
    )

    private fun pump(scene: ImageComposeScene, timeoutMs: Long = 5_000L, until: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!until() && System.nanoTime() < deadline) {
            scene.render(System.nanoTime()).close()
            Thread.sleep(10L)
        }
        assertTrue(until(), "Canvas scene did not reach the requested state")
    }

    @Test(timeout = 15_000) fun pausedRemountCapturesTheObservedStartAndResumesFromIt() {
        ClipServer().use { server ->
            val paused = mutableStateOf(true)
            val capture = mutableStateOf(true)
            val stills = ConcurrentLinkedQueue<Pair<ImageBitmap?, Long>>()
            val resumed = AtomicInteger()
            val policy = MutableStateFlow(DesktopVisualPolicy(DesktopPerformanceMode.BALANCED, true, false))
            val scene = ImageComposeScene(96, 96, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                DesktopCanvasVideo(spec(server.url, paused.value, capture.value,
                    onStill = { image, position -> stills.add(image to position) },
                    onResumed = { resumed.incrementAndGet() }), Modifier.fillMaxSize(), policy)
            }
            try {
                pump(scene) { stills.isNotEmpty() }
                val first = stills.first()
                assertNotNull(first.first, "Capture raced opening and returned no displayed frame")
                assertEquals(750L, first.second)
                assertEquals(1, stills.size)
                assertEquals(0, resumed.get())
                repeat(3) { scene.render(System.nanoTime()).close() }
                scene.render(System.nanoTime()).use { rendered ->
                    val actual = rendered.toComposeImageBitmap().toPixelMap()[48, 48]
                    val image = requireNotNull(first.first)
                    val expected = image.toPixelMap()[image.width / 2, image.height / 2]
                    assertTrue(actual.alpha > 0.98f, "Matching still's native frame was not visible")
                    assertTrue(kotlin.math.abs(actual.red - expected.red) < 0.05f &&
                        kotlin.math.abs(actual.green - expected.green) < 0.05f &&
                        kotlin.math.abs(actual.blue - expected.blue) < 0.05f,
                        "Rendered frame does not match the captured native frame")
                }
                capture.value = false
                paused.value = false
                pump(scene) { resumed.get() > 0 }
                paused.value = true
                capture.value = true
                pump(scene) { stills.size == 2 }
                assertNotNull(stills.last().first)
                assertTrue(stills.last().second > first.second, "Resume did not advance from the captured frame")
            } finally {
                scene.close()
            }
        }
    }

    @Test(timeout = 15_000) fun periodicBackdropCaptureUsesTheLatestFrameAndStopsWhenHidden() {
        ClipServer().use { server ->
            val frames = ConcurrentLinkedQueue<ImageBitmap>()
            val policy = MutableStateFlow(DesktopVisualPolicy(DesktopPerformanceMode.BALANCED, true, false))
            val scene = ImageComposeScene(96, 96, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                DesktopCanvasVideo(spec(server.url, false, false, { _, _ -> }, {},
                    onFrame = { frames.add(it) }, frameCapturePx = 1_000), Modifier.fillMaxSize(), policy)
            }
            try {
                pump(scene) { frames.size >= 8 }
                assertTrue(frames.any { it !== frames.first() }, "Periodic readback retained its first-frame closure")
                policy.value = DesktopVisualPolicy(DesktopPerformanceMode.BALANCED, false, false)
                repeat(5) { scene.render(System.nanoTime()).close(); Thread.sleep(10L) }
                val stoppedAt = frames.size
                Thread.sleep(120L)
                scene.render(System.nanoTime()).close()
                assertEquals(stoppedAt, frames.size, "Hidden clip kept requesting background captures")
            } finally {
                scene.close()
            }
        }
    }

    @Test(timeout = 15_000) fun hiddenClipDoesNotFetchUntilItCanBeSeen() {
        ClipServer().use { server ->
            val stills = ConcurrentLinkedQueue<Pair<ImageBitmap?, Long>>()
            val policy = MutableStateFlow(DesktopVisualPolicy(DesktopPerformanceMode.BALANCED, false, false))
            val scene = ImageComposeScene(96, 96, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                DesktopCanvasVideo(spec(server.url, true, true, { image, position -> stills.add(image to position) }, {}),
                    Modifier.fillMaxSize(), policy)
            }
            try {
                repeat(5) { scene.render(System.nanoTime()).close(); Thread.sleep(10L) }
                assertEquals(0, server.requests.get())
                assertTrue(stills.all { it.first == null })
                policy.value = DesktopVisualPolicy(DesktopPerformanceMode.BALANCED, true, false)
                pump(scene) { server.requests.get() == 1 }
            } finally {
                scene.close()
            }
        }
    }

    @Test fun backdropCaptureHonorsItsBoundWithoutChangingFullResolutionStills() {
        val full = ImageBitmap(64, 96)
        Canvas(full).drawRect(Rect(0f, 0f, 64f, 96f), Paint().apply { color = Color.Red })
        assertNull(full.canvasBackdropCapture(0))
        assertSame(full, full.canvasBackdropCapture(128))
        val small = assertNotNull(full.canvasBackdropCapture(32))
        assertEquals(21, small.width)
        assertEquals(32, small.height)
        assertEquals(Color.Red, small.toPixelMap()[10, 16])
        assertEquals(64, full.width)
        assertEquals(96, full.height)
    }
}
