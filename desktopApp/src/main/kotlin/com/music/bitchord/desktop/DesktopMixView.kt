package com.music.bitchord.desktop

import com.music.bitchord.data.settings.MixDeck
import com.music.bitchord.data.settings.MixTransitionView
import com.music.bitchord.data.settings.MixViewTimeline
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TransitionPlan
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/** Bounded source-PCM summaries. Call on a preparation worker, never on the audio thread. */
internal object DesktopMixView {
    const val WAVE_BINS = 512
    private const val SOURCE_BINS = 4096
    private const val FADER_POINTS = 129

    class Wave(val low: FloatArray, val mid: FloatArray, val high: FloatArray, val start: Double, val end: Double) {
        fun apply(deck: MixDeck) = deck.withWave(low, mid, high, start, end)
    }

    class LoopData(
        private val outgoing: Wave,
        private val incoming: Wave,
        private val clock: LoopMix.Clock,
        private val fadeOut: FloatArray,
        private val fadeIn: FloatArray,
    ) {
        fun view(out: MixDeck, into: MixDeck) = MixTransitionView(
            outgoing.apply(out), incoming.apply(into), 0.0, clock.elementAt, 0.0, clock.elementAt,
            clock.beats + clock.settleBeats,
            FloatArray(clock.beatTimes.size) { (clock.beatTimes[it] / clock.elementAt).toFloat() },
            fadeOut, fadeIn, (clock.handoffAt / clock.elementAt).toFloat(), MixViewTimeline.MIX,
        )
    }

    fun ordinary(
        outgoing: MixDeck,
        incoming: MixDeck,
        plan: TransitionPlan?,
        outStart: Double,
        outEnd: Double,
        inStart: Double,
        inEnd: Double,
        incomingLevel: Float = 1f,
        bassSwapAt: Float = -1f,
        beatMarks: FloatArray = FloatArray(0),
        beats: Int = 0,
    ): MixTransitionView {
        val out = FloatArray(FADER_POINTS)
        val into = FloatArray(FADER_POINTS)
        val gains = FloatArray(2)
        for (i in out.indices) {
            val p = i.toFloat() / out.lastIndex
            if (plan == null) {
                gains[0] = sqrt(1f - p)
                gains[1] = sqrt(p)
            } else DesktopTransitionRide.gains(plan, p, gains)
            val leaving = if (plan != null && plan.echoSeconds > 0.0) DesktopTransitionRide.echoLevel(plan, p) else gains[0]
            val trim = 1f / sqrt((leaving + gains[1]).coerceAtLeast(1f))
            out[i] = gains[0] * trim
            into[i] = gains[1] * trim * incomingLevel
        }
        val observedMarks = if (beatMarks.all { it.isFinite() && it in 0f..1f } &&
            (1 until beatMarks.size).all { beatMarks[it] > beatMarks[it - 1] }) beatMarks.copyOf() else FloatArray(0)
        return MixTransitionView(outgoing, incoming, outStart, outEnd, inStart, inEnd,
            if (observedMarks.isEmpty()) 0 else beats.coerceAtLeast(0), observedMarks,
            out, into, bassSwapAt)
    }

    fun withWaves(
        view: MixTransitionView,
        outgoing: DesktopLoopMixGraph.Region?,
        incoming: DesktopLoopMixGraph.Region?,
        sampleRate: Int,
        cancelled: () -> Boolean = { false },
    ): MixTransitionView? {
        if (sampleRate <= 0 || cancelled()) return null
        val out = outgoing?.let { reduce(it, sampleRate, WAVE_BINS, cancelled) ?: return null }
        val into = incoming?.let { reduce(it, sampleRate, WAVE_BINS, cancelled) ?: return null }
        if (cancelled()) return null
        return view.withDecks(out?.apply(view.outgoing) ?: view.outgoing, into?.apply(view.incoming) ?: view.incoming)
    }

    fun loop(
        clock: LoopMix.Clock,
        outgoing: DesktopLoopMixGraph.Region,
        incoming: DesktopLoopMixGraph.Region,
        sampleRate: Int,
        automation: DesktopLoopMixAutomation,
        cancelled: () -> Boolean,
    ): LoopData? {
        val out = reduce(outgoing, sampleRate, SOURCE_BINS, cancelled) ?: return null
        val into = reduce(incoming, sampleRate, SOURCE_BINS, cancelled) ?: return null
        val mappedOut = remap(out, clock, LoopMix.Side.OUT, cancelled) ?: return null
        val mappedIn = remap(into, clock, LoopMix.Side.IN, cancelled) ?: return null
        val fadeOut = FloatArray(FADER_POINTS) { automation.gain(LoopMix.Side.OUT, it * clock.elementAt / (FADER_POINTS - 1)) }
        val fadeIn = FloatArray(FADER_POINTS) { automation.gain(LoopMix.Side.IN, it * clock.elementAt / (FADER_POINTS - 1)) }
        return if (cancelled()) null else LoopData(mappedOut, mappedIn, clock, fadeOut, fadeIn)
    }

    private fun reduce(region: DesktopLoopMixGraph.Region, rate: Int, bins: Int, cancelled: () -> Boolean): Wave? {
        if (rate <= 0 || !region.start.isFinite() || region.left.isEmpty() || region.left.size != region.right.size) return null
        val low = FloatArray(bins)
        val mid = FloatArray(bins)
        val high = FloatArray(bins)
        val lowStep = 1 - exp(-2 * PI * 200 / rate)
        val highStep = 1 - exp(-2 * PI * 2_500 / rate)
        var lowL = 0.0
        var lowR = 0.0
        var wideL = 0.0
        var wideR = 0.0
        for (i in region.left.indices) {
            if (i % 4096 == 0 && cancelled()) return null
            val left = region.left[i].toDouble()
            val right = region.right[i].toDouble()
            if (!left.isFinite() || !right.isFinite()) return null
            lowL += lowStep * (left - lowL)
            lowR += lowStep * (right - lowR)
            wideL += highStep * (left - wideL)
            wideR += highStep * (right - wideR)
            val bin = (i.toLong() * bins / region.left.size).toInt().coerceAtMost(bins - 1)
            low[bin] = maxOf(low[bin], maxOf(abs(lowL), abs(lowR)).toFloat()).coerceAtMost(1f)
            mid[bin] = maxOf(mid[bin], maxOf(abs(wideL - lowL), abs(wideR - lowR)).toFloat()).coerceAtMost(1f)
            high[bin] = maxOf(high[bin], maxOf(abs(left - wideL), abs(right - wideR)).toFloat()).coerceAtMost(1f)
        }
        return Wave(low, mid, high, region.start, region.start + region.left.size.toDouble() / rate)
    }

    private fun remap(source: Wave, clock: LoopMix.Clock, side: LoopMix.Side, cancelled: () -> Boolean): Wave? {
        val low = FloatArray(WAVE_BINS)
        val mid = FloatArray(WAVE_BINS)
        val high = FloatArray(WAVE_BINS)
        val secondsPerBin = (source.end - source.start) / source.low.size
        for (bin in 0 until WAVE_BINS) {
            if (cancelled()) return null
            var from = bin * clock.elementAt / WAVE_BINS
            val end = (bin + 1) * clock.elementAt / WAVE_BINS
            while (from < end) {
                // Split at beat boundaries so a loop wrap cannot become a spurious source span.
                val nextBeat = clock.beatTimes.firstOrNull { it > from + 1e-9 } ?: end
                val to = minOf(end, nextBeat)
                val a = LoopMix.positionAt(clock, side, from)
                val b = LoopMix.positionAt(clock, side, (to - 1e-9).coerceAtLeast(from))
                if (a != null && b != null) {
                    val first = floor((minOf(a, b) - source.start) / secondsPerBin).toInt().coerceAtLeast(0)
                    val last = ceil((maxOf(a, b) - source.start) / secondsPerBin).toInt().coerceAtMost(source.low.lastIndex)
                    if (first <= last) for (i in first..last) {
                        low[bin] = maxOf(low[bin], source.low[i])
                        mid[bin] = maxOf(mid[bin], source.mid[i])
                        high[bin] = maxOf(high[bin], source.high[i])
                    }
                }
                if (to <= from) return null
                from = to
            }
        }
        return Wave(low, mid, high, 0.0, clock.elementAt)
    }
}
