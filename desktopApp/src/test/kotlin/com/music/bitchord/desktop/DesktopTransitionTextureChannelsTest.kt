package com.music.bitchord.desktop

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopTransitionTextureChannelsTest {
    @Test fun `vibrato modulation clock is independent of channel count`() = checkFrameClock(::DesktopTransitionVibrato)

    @Test fun `phaser modulation clock is independent of channel count`() = checkFrameClock(::DesktopTransitionPhaser)

    @Test fun `flanger modulation clock is independent of channel count`() = checkFrameClock(::DesktopTransitionFlanger)

    @Test fun `flanger cannot read the other stereo channel from its delay`() = checkSilentChannel(::DesktopTransitionFlanger)

    @Test fun `bit reduction preserves a centered mono signal in stereo`() {
        val effect = DesktopTransitionCrush(48_000, 2)
        repeat(4_800) { frame ->
            val sample = (sin(2 * PI * 80 * frame / 48_000) * .8).toFloat()
            val left = effect.process(0, sample)
            val right = effect.process(1, sample)
            assertEquals(left, right, "A shared hold clock must capture both channels at frame $frame")
        }
    }

    @Test fun `vibrato cannot read the other stereo channel from its delay`() = checkSilentChannel(::DesktopTransitionVibrato)

    private fun checkSilentChannel(factory: (Int, Int) -> DesktopTransitionTexture) {
        val effect = factory(48_000, 2)
        var rightPeak = 0f
        repeat(48_000) { frame ->
            val sample = (sin(2 * PI * 80 * frame / 48_000) * .8).toFloat()
            effect.process(0, sample)
            rightPeak = maxOf(rightPeak, abs(effect.process(1, 0f)))
        }
        assertTrue(rightPeak == 0f, "An input channel containing silence acquired peak $rightPeak from its neighbour")
    }

    private fun checkFrameClock(factory: (Int, Int) -> DesktopTransitionTexture) {
        val mono = factory(48_000, 1)
        val stereo = factory(48_000, 2)
        var difference = 0f
        repeat(48_000) { frame ->
            val sample = (sin(2 * PI * 80 * frame / 48_000) * .8).toFloat()
            val expected = mono.process(0, sample)
            difference = maxOf(difference, abs(expected - stereo.process(0, sample)))
            stereo.process(1, 0f)
        }
        assertTrue(difference <= .000001f, "The left channel's modulation changed by $difference when adding a silent channel")
    }
}
