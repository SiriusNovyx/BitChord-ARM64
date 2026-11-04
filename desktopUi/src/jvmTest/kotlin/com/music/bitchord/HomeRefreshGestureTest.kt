package com.music.bitchord

import com.music.bitchord.ui.components.RefreshPullGesture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeRefreshGestureTest {
    @Test fun `wheel requires a settled top followed by deliberate overscroll and idle`() {
        val gesture = RefreshPullGesture(threshold = 100f, idleMs = 180)
        gesture.wheel(200f, atTop = true, nowMs = 0)
        assertFalse(gesture.wheelIdle(180), "Arriving at the top does not refresh")
        assertTrue(gesture.wheelReady)
        for (time in listOf(300L, 400L, 500L, 600L)) gesture.wheel(30f, true, time)
        assertTrue(gesture.wheelArmed)
        assertFalse(gesture.wheelIdle(779))
        assertTrue(gesture.wheelIdle(780))
        assertFalse(gesture.wheelIdle(1_000))
        assertEquals(0f, gesture.distance)
        gesture.wheel(500f, true, 1_100)
        assertFalse(gesture.wheelIdle(1_500), "Cooldown cannot immediately start another refresh")
    }

    @Test fun `fast bursts reversal and leaving the top cannot complete a refresh`() {
        val gesture = RefreshPullGesture(100f)
        fun prepare(time: Long) {
            gesture.wheel(40f, true, time)
            assertFalse(gesture.wheelIdle(time + 240))
        }
        prepare(0)
        for (time in listOf(300L, 320L, 340L)) gesture.wheel(100f, true, time)
        assertFalse(gesture.wheelArmed, "Three fast ordinary notches are not deliberate arming")
        assertFalse(gesture.wheelIdle(580))
        for (time in listOf(700L, 800L, 900L, 1_000L)) gesture.wheel(40f, true, time)
        assertTrue(gesture.wheelArmed)
        gesture.wheel(-1f, true, 1_020)
        assertFalse(gesture.wheelIdle(1_500), "Even a small reversal cancels an armed gesture")
        prepare(2_000)
        for (time in listOf(2_300L, 2_400L, 2_500L, 2_600L)) gesture.wheel(40f, true, time)
        gesture.wheel(40f, false, 2_620)
        assertFalse(gesture.wheelIdle(3_000))
    }

    @Test fun `ordinary navigation and separated wheel ticks never arm refresh`() {
        val gesture = RefreshPullGesture(threshold = 100f)
        gesture.wheel(200f, atTop = false, nowMs = 0)
        assertEquals(0f, gesture.distance)
        gesture.wheel(60f, atTop = true, nowMs = 100)
        assertFalse(gesture.wheelIdle(500))
        gesture.wheel(60f, atTop = true, nowMs = 2_000)
        assertFalse(gesture.wheelIdle(3_000))
    }

    @Test fun `touch release refreshes after threshold but cancellation never does`() {
        val gesture = RefreshPullGesture(threshold = 100f)
        gesture.pull(110f, atTop = true)
        assertTrue(gesture.release())
        gesture.pull(110f, atTop = true)
        gesture.cancel()
        assertFalse(gesture.release())
        gesture.pull(60f, atTop = true)
        gesture.pull(-50f, atTop = true)
        assertFalse(gesture.release())
    }
}
