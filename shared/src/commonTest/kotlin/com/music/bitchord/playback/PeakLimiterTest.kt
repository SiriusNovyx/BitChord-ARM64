package com.music.bitchord.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PeakLimiterTest {
    @Test fun defaultCeilingPreservesExistingEqBehavior() {
        val limiter = PeakLimiter().apply { configure(48_000) }
        assertEquals(PeakLimiter.CEILING / 2, limiter.gainFor(2f))
    }

    @Test fun fullScaleCeilingLeavesOrdinaryPeaksAlone() {
        val limiter = PeakLimiter(1f).apply { configure(48_000) }
        assertEquals(1f, limiter.gainFor(.999f))
        assertEquals(1f, limiter.gainFor(1f))
        assertEquals(.5f, limiter.gainFor(2f))
    }

    @Test fun invalidCeilingsCannotEnterTheAudioChain() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -.1f, 1.01f)) {
            assertFailsWith<IllegalArgumentException> { PeakLimiter(value) }
        }
    }
}
