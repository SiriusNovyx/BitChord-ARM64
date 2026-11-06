package com.music.bitchord

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.withFrameNanos
import com.music.bitchord.ui.player.BackgroundFrameClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackgroundFrameClockTest {
    private class Frames : MonotonicFrameClock {
        var now = 0L
        var requests = 0
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            requests++
            val pulse = 16_666_667L
            now = (now / pulse + 1) * pulse
            return onFrame(now)
        }
    }

    @Test fun `background animation suspends between frames at each explicit limit`() = runBlocking {
        for (fps in listOf(15, 30, 60)) {
            val source = Frames()
            val waits = mutableListOf<Long>()
            val clock = BackgroundFrameClock(source, MutableStateFlow(fps), { source.now }) { millis ->
                waits += millis
                source.now += millis * 1_000_000
            }
            repeat(10) { clock.withFrameNanos {} }
            assertEquals(10, source.requests)
            assertEquals(9, waits.size)
            assertTrue(source.now >= 9 * (1_000_000_000L / fps))
            assertTrue(waits.all { it > 0 })
        }
    }

    @Test fun `pacing does not add a second vsync wait or accumulate display rounding drift`() = runBlocking {
        for (displayHz in listOf(60, 120, 144)) for (fps in listOf(15, 30, 60)) {
            var now = 0L
            val displayPeriod = 1_000_000_000L / displayHz
            val source = object : MonotonicFrameClock {
                override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                    now = (now / displayPeriod + 1) * displayPeriod
                    return onFrame(now)
                }
            }
            val clock = BackgroundFrameClock(source, MutableStateFlow(fps), { now }) { now += it * 1_000_000 }
            var first = 0L
            repeat(61) { index -> clock.withFrameNanos { if (index == 0) first = it } }
            val expected = 60 * (1_000_000_000L / fps)
            assertTrue(kotlin.math.abs(now - first - expected) <= displayPeriod * 2,
                "$fps FPS on $displayHz Hz: expected=$expected observed=${now - first}")
        }
    }

    @Test fun `hidden backgrounds wait without requesting frames or running timers`() = runBlocking {
        val source = Frames()
        val cadence = MutableStateFlow(0)
        var waits = 0
        val clock = BackgroundFrameClock(source, cadence, { source.now }) { waits++ }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { clock.withFrameNanos {} }
        assertEquals(0, source.requests)
        assertEquals(0, waits)
        cadence.value = 30
        job.join()
        assertEquals(1, source.requests)
        cadence.value = 0
        val stopped = launch(start = CoroutineStart.UNDISPATCHED) { clock.withFrameNanos {} }
        stopped.cancelAndJoin()
        assertEquals(1, source.requests)
    }

    @Test fun `relative Compose timestamps cannot bypass system clock pacing`() = runBlocking {
        val origin = 20_000_000_000_000L
        for (fps in listOf(15, 30, 60)) {
            var wall = origin
            val displayPeriod = 8_333_333L
            val source = object : MonotonicFrameClock {
                override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                    wall = origin + ((wall - origin) / displayPeriod + 1) * displayPeriod
                    return onFrame(wall - origin)
                }
            }
            val clock = BackgroundFrameClock(source, MutableStateFlow(fps), { wall }) { wall += it * 1_000_000 }
            val received = mutableListOf<Long>()
            repeat(61) { clock.withFrameNanos { received += it } }
            val expected = 60 * (1_000_000_000L / fps)
            assertTrue(kotlin.math.abs(received.last() - received.first() - expected) <= displayPeriod * 2,
                "$fps FPS must survive the real desktop clock-origin difference")
            assertTrue(received.all { it < origin }, "Animation receives the original Compose clock, not a rewritten origin")
        }
    }

    @Test fun `background frame clock does not pace sibling lyric and input clocks`() = runBlocking {
        val source = Frames()
        var waits = 0
        val background = BackgroundFrameClock(source, MutableStateFlow(15), { source.now }) { source.now += it * 1_000_000; waits++ }
        withContext(source) {
            withContext(background) { repeat(3) { withFrameNanos {} } }
            val before = waits
            repeat(20) { withFrameNanos {} }
            assertEquals(before, waits)
        }
        assertEquals(23, source.requests)
    }
}
