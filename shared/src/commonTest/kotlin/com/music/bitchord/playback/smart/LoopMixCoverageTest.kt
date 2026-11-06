package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LoopMixCoverageTest {
    @Test fun repeatedOverlappingMeasurementsCannotFillAnUnmeasuredGap() {
        val window = CurveWindow(0.0, 10.0, FloatArray(10))
        assertNull(LoopMix.maskStats(listOf(window, window, window), 0.0, 3.0))
    }

    @Test fun anyMeasuredVocalInAnOverlapCountsOnceOnTheTrackTimeline() {
        val clean = CurveWindow(0.0, 10.0, FloatArray(20))
        val sung = CurveWindow(0.5, 10.0, FloatArray(10) { 1f })
        val result = assertNotNull(LoopMix.maskStats(listOf(clean, sung), 0.0, 2.0))
        assertEquals(0.5, result.share, 1e-8)
        assertEquals(1.0, result.longestRunSec, 1e-8)
    }

    @Test fun vocalRunsCannotBridgeARealCleanGapAcrossWindows() {
        val clean = CurveWindow(0.0, 10.0, FloatArray(10))
        val a = CurveWindow(0.0, 10.0, floatArrayOf(1f, 1f))
        val b = CurveWindow(0.4, 10.0, floatArrayOf(1f, 1f))
        val result = assertNotNull(LoopMix.maskStats(listOf(clean, a, b), 0.0, 1.0))
        assertEquals(0.4, result.share, 1e-8)
        assertEquals(0.2, result.longestRunSec, 1e-8)
    }

    @Test fun mixedFrameRatesMeasureRunsInSeconds() {
        val a = CurveWindow(0.0, 10.0, FloatArray(10) { 1f })
        val b = CurveWindow(1.0, 20.0, FloatArray(20) { 1f })
        val result = assertNotNull(LoopMix.maskStats(listOf(a, b), 0.0, 2.0))
        assertEquals(1.0, result.share, 1e-8)
        assertEquals(2.0, result.longestRunSec, 1e-8)
    }

    @Test fun invalidModelValuesAndInvalidTimeRangesRemainUnknown() {
        val invalid = CurveWindow(0.0, 10.0, FloatArray(10) { Float.NaN })
        assertNull(LoopMix.maskStats(listOf(invalid), 0.0, 1.0))
        assertNull(LoopMix.maskStats(listOf(CurveWindow(0.0, 0.0, floatArrayOf(0f))), 0.0, 1.0))
        assertNull(LoopMix.maskStats(emptyList(), 1.0, 1.0))
    }
}
