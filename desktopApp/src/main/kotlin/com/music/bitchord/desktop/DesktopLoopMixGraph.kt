// SPDX-License-Identifier: GPL-3.0-or-later
// Deck setup adapted from LoopMixSession, Copyright (C) 2026 Kushagra Singh.
// Shared LoopMix policy/clock remains AGPL-3.0-or-later; see its source and NOTICE.
package com.music.bitchord.desktop

import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.data.settings.MixDeck
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/** Prepared PCM and automation on one native output clock, independent of Android's two sinks. */
internal class DesktopLoopMixGraph private constructor(
    val plan: LoopMix.Plan,
    val clock: LoopMix.Clock,
    val sampleRate: Int,
    private val renderer: LoopMixRenderer,
    private val mixData: DesktopMixView.LoopData,
    next: Long,
) : AutoCloseable {
    class Region(val left: FloatArray, val right: FloatArray, val start: Double)

    var nextFrame: Long = next
        private set
    val takeoverUs: Long = ((plan.out.loopStart + next.toDouble() / sampleRate) * 1e6).roundToLong()
    val handoverFrame: Long = (clock.elementAt * sampleRate).roundToLong()

    fun render(output: FloatArray, offset: Int, frames: Int) {
        renderer.render(nextFrame, output, offset, frames)
        nextFrame += frames
    }

    fun heard(side: LoopMix.Side): Double = renderer.heard(if (side == LoopMix.Side.OUT) 0 else 1)

    fun mixView(outgoing: MixDeck, incoming: MixDeck) = mixData.view(outgoing, incoming)

    override fun close() = renderer.close()

    companion object {
        fun build(
            plan: LoopMix.Plan,
            sampleRate: Int,
            incomingLevel: Float,
            cancelled: () -> Boolean = { false },
            decode: (LoopMix.Side, Double, Double) -> Region?,
        ): DesktopLoopMixGraph? {
            if (sampleRate <= 0 || !incomingLevel.isFinite() || incomingLevel < 0 ||
                cancelled() || !LoopMixRenderer.available) return null
            val clock = LoopMix.Clock(plan)
            fun region(side: LoopMix.Side, from: Double, to: Double): Region? {
                if (cancelled()) return null
                val wanted = max(0.0, from)
                val pcm = decode(side, wanted, to) ?: return null
                if (cancelled() || !pcm.start.isFinite() || pcm.start < 0 || pcm.start > wanted ||
                    pcm.left.size != pcm.right.size ||
                    pcm.left.size < ceil((to - pcm.start) * sampleRate - 1e-6).toLong()) return null
                return pcm
            }
            val outgoing = region(LoopMix.Side.OUT, plan.out.loopStart - 1, plan.out.loopEnd + 0.5) ?: return null
            val incoming = region(LoopMix.Side.IN, plan.inn.loopStart - 1, clock.elementTrackTime + 3) ?: return null
            if (cancelled()) return null
            val automation = DesktopLoopMixAutomation(clock, incomingLevel)
            val mixData = DesktopMixView.loop(clock, outgoing, incoming, sampleRate, automation, cancelled) ?: return null
            val renderer = LoopMixRenderer(sampleRate)
            if (!renderer.isValid) { renderer.close(); return null }
            try {
                val sr = sampleRate.toDouble()
                fun sample(pcm: Region, seconds: Double) = (seconds - pcm.start) * sr
                val knots = DoubleArray(clock.beats + 1) { clock.beatTimes[it] * sr }
                renderer.setDeck(0, LoopMixRenderer.DeckSetup(
                    outgoing.left, outgoing.right, outgoing.start,
                    sample(outgoing, plan.out.loopStart).roundToLong(),
                    sample(outgoing, plan.out.loopEnd).roundToLong(), 1e18,
                    knots, DoubleArray(knots.size) { sample(outgoing, clock.outPositionAt(it)) },
                    false, clock.releaseAt + 0.05, 0.4, 1f,
                ))
                renderer.setDeck(1, LoopMixRenderer.DeckSetup(
                    incoming.left, incoming.right, incoming.start,
                    sample(incoming, plan.inn.loopStart).roundToLong(),
                    sample(incoming, plan.inn.loopEnd).roundToLong(),
                    sample(incoming, clock.inReleasePosition),
                    knots, DoubleArray(knots.size) { sample(incoming, clock.inPositionAt(it)) },
                    true, clock.elementAt + 2, 0.4, incomingLevel,
                ))
                val h = LoopMix.HANDOVER_SEC
                automation.configure(renderer)
                if (cancelled()) { renderer.close(); return null }
                renderer.prepare()
                renderer.setFadeOut(LoopMixRenderer.IN, clock.elementTrackTime)
                // Warm the stretch before the splice, without advancing a device or a song's playhead.
                val splice = (-h * sr).roundToLong()
                var frame = renderer.firstFrame()
                check(frame <= splice)
                val scratch = FloatArray(1024)
                while (frame < splice) {
                    if (cancelled()) { renderer.close(); return null }
                    val frames = minOf(512L, splice - frame).toInt()
                    renderer.render(frame, scratch, 0, frames)
                    frame += frames
                }
                return DesktopLoopMixGraph(plan, clock, sampleRate, renderer, mixData, frame)
            } catch (failure: Throwable) {
                renderer.close()
                throw failure
            }
        }
    }
}
