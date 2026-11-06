package com.music.bitchord

import com.music.bitchord.ui.player.BackgroundFrameRate
import com.music.bitchord.ui.player.backgroundFramesPerSecond
import kotlin.test.Test
import kotlin.test.assertEquals

class BackgroundFrameRateTest {
    @Test fun `missing and invalid saved values use dynamic`() {
        for (raw in listOf(null, "", "invalid", "120")) assertEquals(BackgroundFrameRate.DYNAMIC, BackgroundFrameRate.stored(raw))
        for (choice in BackgroundFrameRate.entries) assertEquals(choice, BackgroundFrameRate.stored(choice.storageKey))
    }
    @Test fun `explicit limits apply only to a visible renderer`() {
        for ((rate, fps) in listOf(BackgroundFrameRate.FPS_15 to 15, BackgroundFrameRate.FPS_30 to 30, BackgroundFrameRate.FPS_60 to 60)) {
            assertEquals(fps, backgroundFramesPerSecond(rate, visible = true, playing = true, onBattery = false))
            assertEquals(fps, backgroundFramesPerSecond(rate, visible = true, playing = false, onBattery = true))
            assertEquals(0, backgroundFramesPerSecond(rate, visible = false, playing = true, onBattery = false))
        }
    }
    @Test fun `dynamic responds to playback battery and visibility independently`() {
        val rate = BackgroundFrameRate.DYNAMIC
        assertEquals(60, backgroundFramesPerSecond(rate, true, true, false))
        assertEquals(30, backgroundFramesPerSecond(rate, true, true, true))
        assertEquals(15, backgroundFramesPerSecond(rate, true, false, false))
        assertEquals(0, backgroundFramesPerSecond(rate, false, true, true))
    }
}
