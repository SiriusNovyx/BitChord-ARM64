package com.music.bitchord.desktop

import com.music.bitchord.data.settings.PlayerLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPlayerLayoutSettingTest {
    @Test fun `a new settings owner restores either layout without touching playback settings`() {
        val store = DesktopPersistence()
        val setting = DesktopPlayerLayoutSetting(store)
        val original = setting.layout.value
        val volume = store.string("volume", "1.0")
        val output = store.string("output_precision", "PCM_16")
        try {
            for (layout in listOf(PlayerLayout.Classic, PlayerLayout.Modern)) {
                setting.set(layout)
                assertEquals(layout, setting.layout.value)
                assertEquals(layout, DesktopPlayerLayoutSetting(store).layout.value)
                assertEquals(volume, store.string("volume", "1.0"))
                assertEquals(output, store.string("output_precision", "PCM_16"))
            }
        } finally { setting.set(original) }
    }
}
