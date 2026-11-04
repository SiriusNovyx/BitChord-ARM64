// SPDX-License-Identifier: GPL-3.0-or-later
// Desktop boundary for Kushagra Singh's native/loopmix renderer and its shared JNI ABI.
package com.music.bitchord.playback.loopmix

import com.music.bitchord.desktop.DesktopAnalysisRuntime
import com.music.bitchord.desktop.DesktopTrackLog

/** Owns one native two-deck renderer. Configuration precedes ordered, contiguous rendering. */
internal class LoopMixRenderer(val sampleRate: Int) : AutoCloseable {
    init { require(sampleRate > 0) }

    private var handle = if (available) nativeCreate(sampleRate) else 0L
    private var prepared = false
    private var nextFrame = 0L

    val isValid: Boolean @Synchronized get() = handle != 0L

    class DeckSetup(
        val left: FloatArray,
        val right: FloatArray,
        val regionStart: Double,
        val loopStart: Long,
        val loopEnd: Long,
        val releaseAt: Double,
        val knotFrames: DoubleArray,
        val knotPositions: DoubleArray,
        val runOn: Boolean,
        val stopAt: Double,
        val preroll: Double,
        val level: Float,
    )

    @Synchronized fun setDeck(index: Int, deck: DeckSetup) {
        val h = configuring()
        requireDeck(index)
        require(deck.left.size == deck.right.size)
        require(deck.left.all(Float::isFinite) && deck.right.all(Float::isFinite))
        require(deck.regionStart.isFinite() && deck.regionStart >= 0)
        require(deck.loopStart >= 0 && deck.loopEnd >= deck.loopStart && deck.loopEnd <= deck.left.size)
        require(deck.releaseAt >= 0 && !deck.releaseAt.isNaN())
        require(deck.knotFrames.size == deck.knotPositions.size)
        requireIncreasing(deck.knotFrames)
        requireIncreasing(deck.knotPositions)
        require(deck.stopAt >= 0 && !deck.stopAt.isNaN())
        require(deck.preroll.isFinite() && deck.preroll >= 0)
        require(deck.level.isFinite() && deck.level >= 0)
        nativeSetDeck(h, index, deck.left, deck.right, deck.regionStart, deck.loopStart, deck.loopEnd,
            deck.releaseAt, deck.knotFrames, deck.knotPositions, deck.runOn, deck.stopAt, deck.preroll, deck.level)
    }

    @Synchronized fun setEnvelope(
        index: Int, kind: Int, times: DoubleArray, values: FloatArray, exponential: Boolean = false,
    ) {
        val h = configuring()
        requireDeck(index)
        require(kind in KIND_GAIN..KIND_HIGH_PASS_HZ)
        require(times.size == values.size)
        requireIncreasing(times)
        require(values.all { it.isFinite() && (!exponential || it > 0) })
        nativeSetEnvelope(h, index, kind, times, values, exponential)
    }

    @Synchronized fun prepare() {
        val h = configuring()
        nativePrepare(h)
        nextFrame = nativeFirstFrame(h)
        prepared = true
    }

    @Synchronized fun firstFrame(): Long = nativeFirstFrame(renderable())

    /** Offset and count are stereo frames, not interleaved sample indices. */
    @Synchronized fun render(frame: Long, interleaved: FloatArray, offset: Int, frames: Int) {
        val h = renderable()
        require(offset >= 0 && frames >= 0 && offset.toLong() + frames <= interleaved.size / 2)
        require(frame == nextFrame) { "Render blocks must be contiguous: expected $nextFrame, got $frame" }
        val end = Math.addExact(frame, frames.toLong())
        nativeRender(h, frame, interleaved, offset, frames)
        nextFrame = end
    }

    @Synchronized fun setFadeOut(index: Int, trackSeconds: Double) {
        val h = renderable()
        requireDeck(index)
        require(trackSeconds.isFinite())
        nativeSetFadeOut(h, index, trackSeconds)
    }

    @Synchronized fun heard(index: Int): Double {
        val h = renderable()
        requireDeck(index)
        return nativeHeard(h, index)
    }

    // Closing cannot free the native allocation while render owns its critical array access.
    @Synchronized override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) nativeDestroy(h)
    }

    private fun open(): Long = handle.also { check(it != 0L) { "Renderer is closed or unavailable" } }
    private fun configuring(): Long = open().also { check(!prepared) { "Renderer is already prepared" } }
    private fun renderable(): Long = open().also { check(prepared) { "Renderer is not prepared" } }

    companion object {
        const val OUT = 0
        const val IN = 1
        const val KIND_GAIN = 0
        const val KIND_LOW_DB = 1
        const val KIND_HIGH_PASS_HZ = 2

        val available: Boolean = runCatching { DesktopAnalysisRuntime.loadNative("bitchord_loopmix") }
            .onFailure { DesktopTrackLog.log("LoopMix renderer unavailable: ${it.message}") }
            .isSuccess

        private fun requireDeck(index: Int) = require(index in OUT..IN)
        private fun requireIncreasing(values: DoubleArray) {
            require(values.all(Double::isFinite))
            require((1 until values.size).all { values[it] > values[it - 1] })
        }

        @JvmStatic private external fun nativeCreate(sampleRate: Int): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeSetDeck(
            handle: Long, index: Int, left: FloatArray, right: FloatArray, regionStart: Double,
            loopStart: Long, loopEnd: Long, releaseAt: Double, knotFrames: DoubleArray, knotPositions: DoubleArray,
            runOn: Boolean, stopAt: Double, preroll: Double, level: Float,
        )
        @JvmStatic private external fun nativeSetEnvelope(
            handle: Long, index: Int, kind: Int, times: DoubleArray, values: FloatArray, exponential: Boolean,
        )
        @JvmStatic private external fun nativePrepare(handle: Long)
        @JvmStatic private external fun nativeFirstFrame(handle: Long): Long
        @JvmStatic private external fun nativeRender(handle: Long, frame: Long, interleaved: FloatArray, offset: Int, frames: Int)
        @JvmStatic private external fun nativeSetFadeOut(handle: Long, index: Int, trackSeconds: Double)
        @JvmStatic private external fun nativeHeard(handle: Long, index: Int): Double
    }
}
