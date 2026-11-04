package com.music.bitchord.desktop

import com.music.bitchord.ui.player.usesArabicScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSmallUiSettingsTest {
    @Test fun `custom sleep duration accepts inclusive bounds and rejects malformed input`() {
        for (minutes in listOf(5, 17, 60, 719, 720)) {
            assertEquals(minutes, DesktopSleepTimer.parseCustomMinutes(" $minutes "))
        }
        for (input in listOf("", " ", "4", "721", "-5", "+5", "5.0", "5m", "9999999999999999")) {
            assertNull(DesktopSleepTimer.parseCustomMinutes(input), input)
        }
    }

    @Test fun `custom duration reuses timer and replaces after-track mode without another clock`() {
        try {
            DesktopSleepTimer.startAfterTrack()
            DesktopSleepTimer.start(requireNotNull(DesktopSleepTimer.parseCustomMinutes("17")))
            assertEquals(17, DesktopSleepTimer.minutes.value)
            assertFalse(DesktopSleepTimer.afterTrack.value)
            assertTrue(requireNotNull(DesktopSleepTimer.remainingMs()) in 1_019_000L..1_020_000L)
            // Invalid input and cancelling the editor never call start or cancel on the timer.
            assertNull(DesktopSleepTimer.parseCustomMinutes("721"))
            assertEquals(17, DesktopSleepTimer.minutes.value)
            DesktopSleepTimer.startAfterTrack()
            assertNull(DesktopSleepTimer.minutes.value)
            assertNull(DesktopSleepTimer.remainingMs())
            assertTrue(DesktopSleepTimer.afterTrack.value)
        } finally { DesktopSleepTimer.cancel() }
    }

    @Test fun `party visibility defaults on and persists without migrating other preferences`() {
        val store = DesktopPersistence()
        val before = store.preferences.keys().associateWith { store.preferences.get(it, null) }
        try {
            store.preferences.remove("show_listen_together_button")
            store.saveString("player_layout", "classic")
            store.saveString("modern_player", "false")
            store.saveString("lyrics_display_mode", "translation")
            store.saveString("output_precision", "PCM_24")
            store.saveString("volume", "0.37")
            val protected = store.preferences.keys().associateWith { store.preferences.get(it, null) }
            val setting = DesktopOutputPillSetting(store)
            assertTrue(setting.shown.value)
            for (shown in listOf(false, true, false)) {
                setting.set(shown)
                assertEquals(shown, setting.shown.value)
                assertEquals(shown, DesktopOutputPillSetting(store).shown.value)
                assertEquals(protected, store.preferences.keys().filterNot { it == "show_listen_together_button" }
                    .associateWith { store.preferences.get(it, null) })
            }
        } finally {
            store.preferences.keys().forEach(store.preferences::remove)
            before.forEach { (key, value) -> store.preferences.put(key, value) }
        }
    }

    @Test fun `Arabic script detection matches all upstream ranges and leaves Thai Hebrew and Latin alone`() {
        assertTrue(usesArabicScript("سلام دنیا"))
        assertTrue(usesArabicScript("مرحبا بالعالم"))
        for (code in listOf(0x0600, 0x06FF, 0x0750, 0x077F, 0x08A0, 0x08FF, 0xFB50, 0xFDFF, 0xFE70, 0xFEFF)) {
            assertTrue(usesArabicScript(code.toChar().toString()))
        }
        for (text in listOf("", "Hello world", "ภาษาไทย น้ำ กี่ เนื้อเพลง", "שלום")) {
            assertFalse(usesArabicScript(text), text)
        }
    }
}
