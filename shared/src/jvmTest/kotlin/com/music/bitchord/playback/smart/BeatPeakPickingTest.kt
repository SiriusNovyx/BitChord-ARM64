package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals

class BeatPeakPickingTest {
    @Test fun aPositivePlateauProducesOneBeatAtItsCentre() {
        for (length in 2..9) {
            val logits = FloatArray(length + 8)
            for (index in 4 until 4 + length) logits[index] = 3f
            assertEquals(listOf(4.0 + (length - 1) / 2.0), BeatTracker.pickPeaks(logits),
                "A $length-frame flat maximum is one event")
        }
    }

    @Test fun equalPeaksSeparatedByLowerValuesRemainDistinct() {
        val logits = FloatArray(10).apply { this[2] = 3f; this[6] = 3f }
        assertEquals(listOf(2.0, 6.0), BeatTracker.pickPeaks(logits))
    }

    @Test fun anIsolatedPeakRetainsItsSubframeInterpolation() {
        assertEquals(listOf(2.25), BeatTracker.pickPeaks(floatArrayOf(0f, 1f, 4f, 3f, 0f)))
    }

    @Test fun plateausAtEitherBoundaryStillRepresentOneEvent() {
        assertEquals(listOf(1.0), BeatTracker.pickPeaks(floatArrayOf(3f, 3f, 3f, 0f, 0f)))
        assertEquals(listOf(3.0), BeatTracker.pickPeaks(floatArrayOf(0f, 0f, 3f, 3f, 3f)))
    }
}
