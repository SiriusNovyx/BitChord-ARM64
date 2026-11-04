package com.music.bitchord.playback.loopmix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class LoopMixRendererTest {
    private val rate = 48_000

    private fun deck() = LoopMixRenderer.DeckSetup(
        left = FloatArray(rate * 4) { (0.15 * sin(2 * PI * 110 * it / rate)).toFloat() },
        right = FloatArray(rate * 4) { (0.075 * sin(2 * PI * 110 * it / rate)).toFloat() },
        regionStart = 0.0, loopStart = rate / 2L, loopEnd = rate.toLong(), releaseAt = rate * 2.0,
        knotFrames = doubleArrayOf(0.0, rate * 2.0),
        knotPositions = doubleArrayOf(rate / 2.0, rate * 2.5),
        runOn = true, stopAt = 3.0, preroll = 0.0, level = 1f,
    )

    @Test fun closedRendererRejectsEveryNativeOperationAndCloseIsIdempotent() {
        val renderer = LoopMixRenderer(rate)
        assertTrue("Real native library must be available for this regression", renderer.isValid)
        renderer.close()
        renderer.close()
        assertFalse(renderer.isValid)
        assertThrows(IllegalStateException::class.java) { renderer.prepare() }
        assertThrows(IllegalStateException::class.java) { renderer.firstFrame() }
        assertThrows(IllegalStateException::class.java) { renderer.heard(0) }
        assertThrows(IllegalStateException::class.java) { renderer.render(0, FloatArray(2), 0, 1) }
        assertThrows(IllegalStateException::class.java) { renderer.setFadeOut(0, 1.0) }
    }

    @Test fun invalidConfigurationNeverReachesNativeArrayAccess() {
        assertThrows(IllegalArgumentException::class.java) { LoopMixRenderer(0) }
        LoopMixRenderer(rate).use { renderer ->
            val bad = deck()
            assertThrows(IllegalArgumentException::class.java) { renderer.setDeck(2, bad) }
            assertThrows(IllegalArgumentException::class.java) {
                renderer.setEnvelope(0, 0, doubleArrayOf(1.0, 0.0), floatArrayOf(0f, 1f))
            }
            assertThrows(IllegalArgumentException::class.java) {
                renderer.setEnvelope(0, 0, doubleArrayOf(0.0), floatArrayOf())
            }
            assertThrows(IllegalArgumentException::class.java) {
                renderer.setEnvelope(0, 2, doubleArrayOf(0.0), floatArrayOf(0f), true)
            }
            assertThrows(IllegalStateException::class.java) { renderer.render(0, FloatArray(2), 0, 1) }
        }
    }

    @Test fun actualRenderIsContiguousBoundedStereoAndMapsObservedPosition() {
        LoopMixRenderer(rate).use { renderer ->
            renderer.setDeck(0, deck())
            renderer.setEnvelope(0, 0, doubleArrayOf(0.0, 3.0), floatArrayOf(1f, 1f))
            renderer.prepare()
            assertThrows(IllegalStateException::class.java) { renderer.setDeck(0, deck()) }
            assertThrows(IllegalStateException::class.java) { renderer.prepare() }
            var frame = renderer.firstFrame()
            val output = FloatArray(516 * 2) { 42f }
            assertThrows(IllegalArgumentException::class.java) { renderer.render(frame, output, -1, 2) }
            assertThrows(IllegalArgumentException::class.java) { renderer.render(frame, output, 2, Int.MAX_VALUE) }
            assertThrows(IllegalArgumentException::class.java) { renderer.render(frame + 1, output, 2, 512) }
            var peak = 0f
            var observedActive = 0
            while (frame < rate * 4) {
                renderer.render(frame, output, 2, 512)
                assertEquals(42f, output[0], 0f)
                assertEquals(42f, output[1], 0f)
                assertEquals(42f, output[1028], 0f)
                assertEquals(42f, output[1029], 0f)
                for (i in 0 until 512) {
                    val left = output[4 + i * 2]
                    val right = output[5 + i * 2]
                    assertTrue(left.isFinite() && right.isFinite())
                    assertEquals(left * 0.5f, right, 1e-4f)
                    peak = maxOf(peak, abs(left))
                    if (abs(left) > 1e-5) observedActive++
                }
                frame += 512
            }
            assertTrue("Native PCM must contain real sustained audio", observedActive > rate)
            assertTrue("Generated fixture peak", peak in 0.01f..0.5f)
            assertTrue("Actual observed deck position", renderer.heard(0) > 1.0)
            assertThrows(IllegalArgumentException::class.java) { renderer.render(frame - 512, output, 2, 512) }
        }
    }
}
