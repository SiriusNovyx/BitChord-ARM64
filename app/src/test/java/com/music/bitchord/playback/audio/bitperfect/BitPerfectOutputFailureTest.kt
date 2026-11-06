package com.music.bitchord.playback.audio.bitperfect

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.BitPerfectMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitPerfectOutputFailureTest {
    @Suppress("UNCHECKED_CAST")
    private fun failures(): MutableSet<String> = BitPerfectOutput::class.java
        .getDeclaredMethod("currentFailures", BitPerfectMode::class.java, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }
        .invoke(BitPerfectOutput, BitPerfectMode.USB_DIRECT, 73) as MutableSet<String>

    @Test
    fun acceptingFixedVolumeRetriesThePreviouslyRefusedFormat() {
        val oldConsent = AppSettings.usbAllowFixedVolume.value
        val oldConvert = AppSettings.convertForLimitedDac.value
        try {
            AppSettings.convertForLimitedDac.value = false
            AppSettings.usbAllowFixedVolume.value = false
            failures().apply { clear(); add("44100/2/24") }
            assertTrue(failures().contains("44100/2/24"))

            AppSettings.usbAllowFixedVolume.value = true
            assertFalse(failures().contains("44100/2/24"))

            failures().add("48000/2/24")
            assertTrue(failures().contains("48000/2/24"))
        } finally {
            failures().clear()
            AppSettings.usbAllowFixedVolume.value = oldConsent
            AppSettings.convertForLimitedDac.value = oldConvert
        }
    }
}
