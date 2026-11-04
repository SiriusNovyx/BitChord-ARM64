package com.music.bitchord.desktop

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Converts interleaved float audio from one sample rate to another, keeping its pitch.
 *
 * The output stream is opened at the rate of whichever track started it, and a track decodes at its
 * own rate — YouTube's Opus at 48 kHz, most lossless at 44.1 kHz, hi-res higher still. Samples of
 * one rate written to a stream of another play back faster or slower, and so sharp or flat. Android
 * never meets this because each of its players has its own output; here an Automix handoff keeps
 * the one stream, so the incoming track has to be brought to its rate.
 *
 * A windowed-sinc interpolator: band-limited to the lower of the two Nyquists, so a downward
 * conversion does not alias.
 */
internal class DesktopRateConverter(
    val fromRate: Int,
    val toRate: Int,
    private val channels: Int,
) {
    /** Input frames advanced per output frame. */
    private val step = fromRate.toDouble() / toRate

    /** Cutoff as a fraction of the input's Nyquist; just under the lower of the two rates'. */
    private val cutoff = minOf(1.0, toRate.toDouble() / fromRate) * CUTOFF_MARGIN

    /** How far either side of an output frame the kernel reaches, in input frames. */
    private val reach = ZERO_CROSSINGS / cutoff
    private val reachFrames = kotlin.math.ceil(reach).toInt()

    /** The kernel from 0 to [reach], sampled [TABLE_RESOLUTION] times per input frame. */
    private val table = FloatArray((reach * TABLE_RESOLUTION).toInt() + 2) { index ->
        val x = index.toDouble() / TABLE_RESOLUTION
        if (x >= reach) {
            0f
        } else {
            val arg = PI * cutoff * x
            val sinc = if (x == 0.0) 1.0 else sin(arg) / arg
            // Blackman over the kernel's span.
            val w = 0.42 + 0.5 * cos(PI * x / reach) + 0.08 * cos(2 * PI * x / reach)
            (cutoff * sinc * w).toFloat()
        }
    }

    /** Input held for the kernel's reach, starting [reachFrames] of silence before the first frame. */
    private var held = FloatArray(4_096 * channels)
    private var heldFrames = reachFrames
    /** Where the next output frame sits, in frames of [held]. */
    private var position = reachFrames.toDouble()
    private var output = FloatArray(0)
    private var inputFrames = 0L
    private var outputFrames = 0L
    private var finished = false

    var outputCount: Int = 0
        private set

    fun process(input: FloatArray, count: Int): FloatArray {
        check(!finished) { "A completed rate converter cannot accept a new stream" }
        inputFrames += count / channels
        append(input, count)
        val capacity = (((heldFrames - position) / step).toInt() + 2) * channels
        if (output.size < capacity) output = FloatArray(capacity)
        var produced = 0
        while (floor(position).toInt() + reachFrames < heldFrames) {
            render(position, produced)
            produced += channels
            position += step
        }
        // Keep what the next output frame's kernel still reaches back to.
        val keepFrom = (floor(position).toInt() - reachFrames).coerceAtLeast(0)
        if (keepFrom > 0) {
            System.arraycopy(held, keepFrom * channels, held, 0, (heldFrames - keepFrom) * channels)
            heldFrames -= keepFrom
            position -= keepFrom
        }
        outputCount = produced
        outputFrames += produced / channels
        return output
    }

    /** Complete the real input's duration using zero extension for the kernel's remaining support. */
    fun finish(): FloatArray {
        outputCount = 0
        if (finished) return output
        finished = true
        val totalFrames = (inputFrames * toRate + fromRate - 1) / fromRate
        val remaining = (totalFrames - outputFrames).coerceAtLeast(0).toInt()
        if (remaining == 0) return output
        val paddedFrames = heldFrames + reachFrames + 1
        if (held.size < paddedFrames * channels) held = held.copyOf(paddedFrames * channels)
        held.fill(0f, heldFrames * channels, paddedFrames * channels)
        if (output.size < remaining * channels) output = FloatArray(remaining * channels)
        repeat(remaining) { frame ->
            render(position, frame * channels)
            position += step
        }
        outputCount = remaining * channels
        outputFrames += remaining
        return output
    }

    private fun render(at: Double, into: Int) {
        val centre = floor(at).toInt()
        val fraction = at - centre
        for (channel in 0 until channels) output[into + channel] = 0f
        var k = centre - reachFrames + 1
        val last = centre + reachFrames
        while (k <= last) {
            val distance = kotlin.math.abs(fraction - (k - centre))
            val scaled = distance * TABLE_RESOLUTION
            val index = scaled.toInt()
            if (index < table.size - 1) {
                val blend = (scaled - index).toFloat()
                val weight = table[index] + (table[index + 1] - table[index]) * blend
                val base = k * channels
                for (channel in 0 until channels) output[into + channel] += held[base + channel] * weight
            }
            k++
        }
    }

    private fun append(input: FloatArray, count: Int) {
        val needed = heldFrames * channels + count
        if (held.size < needed) held = held.copyOf(maxOf(needed, held.size * 2))
        System.arraycopy(input, 0, held, heldFrames * channels, count)
        heldFrames += count / channels
    }

    private companion object {
        /** Kernel half-width in zero crossings: a long enough skirt for a clean stopband. */
        const val ZERO_CROSSINGS = 24
        const val TABLE_RESOLUTION = 512
        /** Pulls the cutoff a little under Nyquist so the transition band is not folded back. */
        const val CUTOFF_MARGIN = 0.95
    }
}
