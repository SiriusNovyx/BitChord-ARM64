package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class V19TransitionPolicyContractTest {
    @Test fun incomingRateKeepsTheAsymmetricV19StretchWindow() {
        assertEquals(.9, incomingRateBetween(120.0, 120.0 / .9), 1e-9)
        assertEquals(1.25, incomingRateBetween(120.0, 96.0), 1e-9)
        assertTrue(stretchReachable(120.0, 120.0 / .9))
        assertTrue(stretchReachable(120.0, 96.0))
        assertFalse(stretchReachable(120.0, 120.0 / .89))
        assertFalse(stretchReachable(120.0, 120.0 / 1.26))
        assertFalse(stretchReachable(0.0, 120.0))
    }

    @Test fun heuristicMaskRescalingPreservesItsOriginalVocalDecision() {
        assertEquals(0.0, heuristicVocalOnModelScale(0.0))
        assertEquals(VOCAL_ACTIVE_THRESHOLD, heuristicVocalOnModelScale(.6), 1e-9)
        assertEquals(1.0, heuristicVocalOnModelScale(1.0))
        for (value in listOf(0.0, .2, .5, .599, .6, .8, 1.0)) {
            assertEquals(value >= .6, heuristicVocalOnModelScale(value) >= VOCAL_ACTIVE_THRESHOLD,
                "Vocal classification for legacy DSP value $value")
        }
    }
}
