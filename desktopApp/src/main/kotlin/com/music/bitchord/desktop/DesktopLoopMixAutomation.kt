// SPDX-License-Identifier: GPL-3.0-or-later
// Automation adapted from LoopMixSession, Copyright (C) 2026 Kushagra Singh.
package com.music.bitchord.desktop

import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.smart.LoopMix
import kotlin.math.pow

/** The renderer and its diagnostic chart read the same automation knots. */
internal class DesktopLoopMixAutomation(private val clock: LoopMix.Clock, private val incomingLevel: Float) {
    class Envelope(val times: DoubleArray, val values: FloatArray, val exponential: Boolean = false) {
        fun at(time: Double): Float {
            if (time <= times.first()) return values.first()
            if (time >= times.last()) return values.last()
            val next = times.indexOfFirst { it > time }
            val previous = next - 1
            val fraction = (time - times[previous]) / (times[next] - times[previous])
            val a = values[previous].toDouble()
            val b = values[next].toDouble()
            return (if (exponential) a * (b / a).pow(fraction) else a + (b - a) * fraction).toFloat()
        }
    }

    val outgoingGain = Envelope(
        doubleArrayOf(-LoopMix.HANDOVER_SEC, 0.0, clock.enterEnd, clock.rampEnd, clock.releaseAt),
        floatArrayOf(0f, 1f, 1f, .6f, 0f),
    )
    val incomingGain = Envelope(doubleArrayOf(0.0, clock.enterEnd, clock.rampEnd), floatArrayOf(0f, .55f, 1f))
    private val swapTimes = doubleArrayOf(clock.handoffAt - clock.swapBar / 2, clock.handoffAt + clock.swapBar / 2)
    val outgoingLow = Envelope(swapTimes, floatArrayOf(0f, LoopMix.BASS_CUT_DB.toFloat()))
    val incomingLow = Envelope(swapTimes, floatArrayOf(LoopMix.BASS_CUT_DB.toFloat(), 0f))
    val outgoingHighPass = Envelope(doubleArrayOf(clock.rampEnd, clock.releaseAt),
        floatArrayOf(LoopMix.HIGH_PASS_FROM_HZ.toFloat(), LoopMix.HIGH_PASS_TO_HZ.toFloat()), true)

    fun configure(renderer: LoopMixRenderer) {
        fun set(deck: Int, kind: Int, envelope: Envelope) = renderer.setEnvelope(
            deck, kind, envelope.times, envelope.values, envelope.exponential,
        )
        set(0, LoopMixRenderer.KIND_GAIN, outgoingGain)
        set(1, LoopMixRenderer.KIND_GAIN, incomingGain)
        set(0, LoopMixRenderer.KIND_LOW_DB, outgoingLow)
        set(1, LoopMixRenderer.KIND_LOW_DB, incomingLow)
        set(0, LoopMixRenderer.KIND_HIGH_PASS_HZ, outgoingHighPass)
    }

    fun gain(side: LoopMix.Side, time: Double): Float = if (side == LoopMix.Side.OUT) {
        outgoingGain.at(time)
    } else {
        // Past elementAt the native deck crosses into the ordinary incoming decoder in 25 ms.
        val join = ((clock.elementAt + LoopMix.HANDOVER_SEC - time) / LoopMix.HANDOVER_SEC).coerceIn(0.0, 1.0)
        incomingGain.at(time) * incomingLevel * join.toFloat()
    }
}
