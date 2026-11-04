package com.music.bitchord.desktop

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

class DesktopCanvasDecoderTest {
    @Test(timeout = 10_000)
    fun capturedFrameRestartsAtTheSameObservedTimestamp() {
        val clip = Files.createTempFile("bitchord-canvas-seek-", ".mp4")
        try {
            requireNotNull(javaClass.getResourceAsStream("/canvas/timed.mp4")).use { source ->
                Files.newOutputStream(clip).use(source::copyTo)
            }
            val expected = DesktopCanvasDecoder().use { decoder ->
                decoder.open(clip.toString()).getOrThrow()
                val pixels = ByteArray(decoder.width * decoder.height * 4)
                repeat(7) { assertTrue(decoder.nextFrame(pixels)) }
                assertEquals(750L, decoder.positionMs)
                pixels.copyOf()
            }
            DesktopCanvasDecoder().use { decoder ->
                decoder.open(clip.toString()).getOrThrow()
                assertTrue(decoder.seekTo(750L))
                val pixels = ByteArray(decoder.width * decoder.height * 4)
                assertTrue(decoder.nextFrame(pixels))
                assertEquals(750L, decoder.positionMs)
                assertContentEquals(expected, pixels, "Reopened clip did not resume from its captured frame")
                assertTrue(decoder.nextFrame(pixels))
                assertEquals(875L, decoder.positionMs)
                assertTrue(decoder.seekTo(0L))
                assertTrue(decoder.nextFrame(pixels))
                assertEquals(0L, decoder.positionMs)
                repeat(25) { assertTrue(decoder.nextFrame(pixels)) }
                assertTrue(requireNotNull(decoder.positionMs) < 3_000L, "EOF loop retained the previous clip cycle's position")
            }
        } finally {
            Files.deleteIfExists(clip)
        }
    }

    @Test(timeout = 15_000)
    fun cancelledNativeOpenReleasesDecoderBeforeFramesArePresented() = runBlocking {
        val clip = Files.createTempFile("bitchord-canvas-cancel-", ".mp4")
        val decoder = DesktopCanvasDecoder()
        val opened = CompletableDeferred<Unit>()
        val releaseOpen = CountDownLatch(1)
        val presented = AtomicBoolean()
        try {
            requireNotNull(javaClass.getResourceAsStream("/canvas/red.mp4")).use { source ->
                Files.newOutputStream(clip).use(source::copyTo)
            }
            val job = launch(Dispatchers.Default) {
                withDesktopCanvasResource(decoder) {
                    withContext(Dispatchers.IO) {
                        decoder.open(clip.toString()).getOrThrow()
                        opened.complete(Unit)
                        check(releaseOpen.await(10, TimeUnit.SECONDS))
                    }
                    presented.set(true)
                }
            }
            try {
                withTimeout(5_000) { opened.await() }
                job.cancel()
                releaseOpen.countDown()
                withTimeout(5_000) { job.join() }
                assertFalse(presented.get())
                assertFalse(decoder.nextFrame(ByteArray(64 * 64 * 4)), "Cancelled opening left a live native decoder")
            } finally {
                releaseOpen.countDown()
                withContext(NonCancellable) { job.cancelAndJoin() }
            }
        } finally {
            decoder.close()
            Files.deleteIfExists(clip)
        }
    }

    @Test(timeout = 10_000)
    fun nativeVideoDecodesAndLoopsWithCorrectPixelChannels() {
        val clip = Files.createTempFile("bitchord-canvas-test-", ".mp4")
        val decoder = DesktopCanvasDecoder()
        try {
            requireNotNull(javaClass.getResourceAsStream("/canvas/red.mp4")).use { source ->
                Files.newOutputStream(clip).use(source::copyTo)
            }
            decoder.open(clip.toString()).getOrThrow()
            assertEquals(64, decoder.width)
            assertEquals(64, decoder.height)
            val pixels = ByteArray(decoder.width * decoder.height * 4)
            // The fixture has six frames; crossing EOF also checks the native seek/flush path.
            repeat(20) {
                assertTrue(decoder.nextFrame(pixels), "frame $it was unavailable")
                assertTrue((pixels[2].toInt() and 255) > 240, "red channel is missing")
                assertTrue((pixels[0].toInt() and 255) < 15, "blue channel is swapped")
                assertEquals(255, pixels[3].toInt() and 255)
            }
        } finally {
            decoder.close()
            Files.deleteIfExists(clip)
        }
    }
}
