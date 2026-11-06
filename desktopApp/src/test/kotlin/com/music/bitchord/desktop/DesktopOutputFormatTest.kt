package com.music.bitchord.desktop

import com.music.bitchord.ui.player.OutputFormatUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopOutputFormatTest {
    private fun DesktopAudioPipeline.output(): OutputFormatUi {
        val mapper = Class.forName("com.music.bitchord.desktop.DesktopPlayerHostKt")
            .getDeclaredMethod("toOutputFormat", DesktopAudioPipeline::class.java)
        mapper.isAccessible = true
        return mapper.invoke(null, this) as OutputFormatUi
    }

    private fun OutputFormatUi.claimsDirectOutput(): Boolean = javaClass.methods.any {
        it.name in setOf("getCarriesHiRes", "getBitPerfect", "getDacLimited") && it.invoke(this) == true
    }

    @Test fun floatStereoSharedOutputDoesNotEstablishDirectPlayback() {
        val output = DesktopAudioPipeline(outputIsFloat = true, outputBytesPerSample = 4,
            decodedSampleRateHz = 48_000, outputSampleRateHz = 48_000, outputChannels = 2).output()
        assertEquals("32-bit float · 48 kHz", output.summary)
        assertFalse("A float sample container does not establish exclusive playback", output.claimsDirectOutput())
    }

    @Test fun resampledHighResolutionInputDoesNotEstablishDirectPlayback() {
        val output = DesktopAudioPipeline(outputIsFloat = true, outputBytesPerSample = 4,
            decodedSampleRateHz = 96_000, outputSampleRateHz = 48_000).output()
        assertEquals("32-bit float · 48 kHz", output.summary)
        assertFalse("The observed output rate must not inherit source-resolution claims", output.claimsDirectOutput())
    }

    @Test fun wideHighRateSharedOutputStillDoesNotProveExclusivePlayback() {
        val output = DesktopAudioPipeline(outputIsFloat = false, outputBytesPerSample = 3,
            decodedSampleRateHz = 96_000, outputSampleRateHz = 96_000,
            deviceName = "USB DAC").output()
        assertEquals("24-bit PCM · 96 kHz", output.summary)
        assertFalse("A USB name and matching high sample rates are insufficient evidence", output.claimsDirectOutput())
    }

    @Test fun unknownOutputHasNoInventedFormatOrDirectClaim() {
        val output = DesktopAudioPipeline().output()
        assertEquals("", output.summary)
        assertFalse(output.claimsDirectOutput())
    }

    @Test fun narrowFallbackRetainsTheActuallyOpenedFormat() {
        val output = DesktopAudioPipeline(outputIsFloat = false, outputBytesPerSample = 2,
            decodedSampleRateHz = 96_000, outputSampleRateHz = 44_100).output()
        assertEquals("16-bit PCM · 44.1 kHz", output.summary)
        assertFalse(output.claimsDirectOutput())
    }

    @Test fun dspDoesNotTurnSharedOutputIntoDirectPlayback() {
        val output = DesktopAudioPipeline(outputIsFloat = true, outputBytesPerSample = 4,
            outputSampleRateHz = 96_000, equalizerEnabled = true, skipSilence = true).output()
        assertEquals("32-bit float · 96 kHz", output.summary)
        assertFalse(output.claimsDirectOutput())
    }

    @Test fun directAndDacLimitedEvidenceRemainDistinctInTheContract() {
        val exact = OutputFormatUi(bitPerfect = true)
        val converted = OutputFormatUi(dacLimited = true)
        assertTrue(exact.bitPerfect)
        assertFalse(exact.dacLimited)
        assertFalse(converted.bitPerfect)
        assertTrue(converted.dacLimited)
        assertTrue(exact.claimsDirectOutput())
        assertTrue(converted.claimsDirectOutput())
    }
}
