package com.music.bitchord.desktop

import com.music.bitchord.data.settings.MixDeck
import com.music.bitchord.data.settings.MixViewTimeline
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class DesktopMixViewTest {
    private val rate = 48_000
    private fun deck(title: String) = MixDeck(title, "Artist", null, 120.0, "C major", 120_000, 0.0, 0.0)
    private fun plan(): LoopMix.Plan {
        fun pick(start: Double) = LoopMix.Pick(start, start + 8, 4, .5, 120.0, 0.0, LoopMix.Check.MASK)
        return LoopMix.Plan(pick(90.0), pick(2.0), 1.0, 4, 2, 2, 8)
    }
    private fun region(from: Double, to: Double, frequency: Double = 110.0,
                       amplitude: (Double) -> Double = { .5 }): DesktopLoopMixGraph.Region {
        val n = kotlin.math.ceil((to - from) * rate).toInt()
        val left = FloatArray(n) { i ->
            val t = from + i.toDouble() / rate
            (sin(2 * PI * frequency * t) * amplitude(t)).toFloat()
        }
        return DesktopLoopMixGraph.Region(left, left.copyOf(), from)
    }

    @Test fun ordinaryViewUsesActualRideGainsAndOverlapProtection() {
        val plan = TransitionPlan(fadeSeconds = 8.0, transitionBeats = 16,
            transitionStyle = TransitionStyle.DJ_BLEND, bassSwap = true)
        val view = DesktopMixView.ordinary(deck("Out"), deck("In"), plan, 90.0, 98.0, 2.0, 10.0, .75f, .5f)
        assertEquals(MixViewTimeline.SOURCE, view.timeline)
        assertEquals(90.0, view.outStart, 0.0)
        assertEquals(10.0, view.inEnd, 0.0)
        val gains = FloatArray(2)
        for (i in view.fadeIn.indices) {
            val p = i.toFloat() / view.fadeIn.lastIndex
            DesktopTransitionRide.gains(plan, p, gains)
            val trim = 1f / sqrt((gains[0] + gains[1]).coerceAtLeast(1f))
            assertEquals(gains[0] * trim, view.fadeOut[i], 1e-6f)
            assertEquals(gains[1] * trim * .75f, view.fadeIn[i], 1e-6f)
        }
        assertEquals(.5f, view.bassSwapAt, 0f)
        assertTrue(view.outgoing.low.isEmpty())
    }

    @Test fun unknownAndCancelledWaveformsAreNotInvented() {
        val view = DesktopMixView.ordinary(deck("Out"), deck("In"), null, 90.0, 98.0, 2.0, 10.0)
        val withoutPcm = DesktopMixView.withWaves(view, null, null, rate)!!
        assertTrue(withoutPcm.outgoing.low.isEmpty())
        assertTrue(withoutPcm.incoming.high.isEmpty())
        assertNull(DesktopMixView.withWaves(view, region(90.0, 98.0), null, rate) { true })
    }

    @Test fun shortenedOrUnmeasuredSpanDoesNotInventAUniformBeatGrid() {
        val plan = TransitionPlan(fadeSeconds = 8.0, transitionBeats = 16)
        val unknown = DesktopMixView.ordinary(deck("Out"), deck("In"), plan, 94.3, 96.3, 2.0, 4.0)
        assertEquals(0, unknown.beats)
        assertTrue(unknown.beatMarks.isEmpty())
        val measured = floatArrayOf(.1f, .35f, .6f, .85f)
        val observed = DesktopMixView.ordinary(deck("Out"), deck("In"), plan, 94.3, 96.3, 2.0, 4.0,
            beatMarks = measured, beats = 3)
        assertEquals(3, observed.beats)
        assertArrayEquals(measured, observed.beatMarks, 0f)
        measured[0] = .9f
        assertEquals(.1f, observed.beatMarks[0], 0f)
        val invalid = DesktopMixView.ordinary(deck("Out"), deck("In"), plan, 94.3, 96.3, 2.0, 4.0,
            beatMarks = floatArrayOf(.2f, Float.NaN), beats = 16)
        assertTrue(invalid.beatMarks.isEmpty())
        assertEquals(0, invalid.beats)
    }

    @Test fun measuredBandsDistinguishRealLowAndHighFrequencyPcm() {
        val view = DesktopMixView.ordinary(deck("Out"), deck("In"), null, 90.0, 98.0, 2.0, 10.0)
        val measured = DesktopMixView.withWaves(view, region(90.0, 98.0, 60.0), region(2.0, 10.0, 8_000.0), rate)!!
        assertEquals(DesktopMixView.WAVE_BINS, measured.outgoing.low.size)
        assertTrue(measured.outgoing.low.max() > measured.outgoing.high.max() * 4)
        assertTrue(measured.incoming.high.max() > measured.incoming.low.max() * 4)
        assertEquals(90.0, measured.outgoing.waveStart, 0.0)
        assertEquals(10.0, measured.incoming.waveEnd, 0.0)
    }

    @Test fun graphViewRepeatsMeasuredRegionsOnTheActualMixClock() {
        val plan = plan()
        val graph = DesktopLoopMixGraph.build(plan, rate, .7f) { side, from, to ->
            region(from, to) { t ->
                if (side == LoopMix.Side.OUT && t - plan.out.loopStart >= 4) .03 else .5
            }
        }!!
        graph.use {
            val view = it.mixView(deck("Out"), deck("In"))
            assertEquals(MixViewTimeline.MIX, view.timeline)
            assertEquals(0.0, view.outStart, 0.0)
            assertEquals(it.clock.elementAt, view.outEnd, 0.0)
            fun peak(at: Double): Float = view.outgoing.low[
                (at / view.outEnd * view.outgoing.low.size).toInt().coerceIn(view.outgoing.low.indices)]
            assertTrue(peak(1.0) > peak(5.0) * 5)
            assertTrue(peak(9.0) > peak(13.0) * 5)
            assertEquals(peak(1.0), peak(9.0), .03f)
            val afterRelease = (it.clock.releaseAt / view.outEnd * view.outgoing.low.size).toInt() + 1
            assertTrue(view.outgoing.low.drop(afterRelease).all { value -> value == 0f })
            assertEquals(.7f, view.fadeIn[view.fadeIn.lastIndex - 1], .03f)
            assertEquals(0f, view.fadeOut.last(), 0f)
        }
    }

    @Test fun loopAutomationKeepsRendererKnotsAndLevels() {
        val clock = LoopMix.Clock(plan())
        val automation = DesktopLoopMixAutomation(clock, .7f)
        assertEquals(0f, automation.gain(LoopMix.Side.OUT, -LoopMix.HANDOVER_SEC), 0f)
        assertEquals(1f, automation.gain(LoopMix.Side.OUT, clock.enterEnd), 0f)
        assertEquals(.6f, automation.gain(LoopMix.Side.OUT, clock.rampEnd), 0f)
        assertEquals(.7f * .55f, automation.gain(LoopMix.Side.IN, clock.enterEnd), 1e-6f)
        assertEquals(.7f, automation.gain(LoopMix.Side.IN, clock.rampEnd), 1e-6f)
        assertEquals(0f, automation.gain(LoopMix.Side.OUT, clock.releaseAt), 0f)
    }
}
