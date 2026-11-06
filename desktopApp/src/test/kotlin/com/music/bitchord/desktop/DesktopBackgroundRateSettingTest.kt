package com.music.bitchord.desktop

import com.music.bitchord.ui.player.BackgroundFrameRate
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopBackgroundRateSettingTest {
    @Test fun `choice survives a new setting owner without changing performance mode`() {
        val saved = mutableMapOf("desktop_performance_mode" to "PERFORMANCE")
        fun owner() = DesktopBackgroundRateSetting({ saved["background_animation_fps"] }, { saved["background_animation_fps"] = it })
        val first = owner()
        assertEquals(BackgroundFrameRate.DYNAMIC, first.rate.value)
        first.set(BackgroundFrameRate.FPS_30)
        assertEquals(BackgroundFrameRate.FPS_30, owner().rate.value)
        assertEquals("PERFORMANCE", saved["desktop_performance_mode"])
    }
}
