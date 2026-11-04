package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.BeatSide
import com.music.bitchord.playback.smart.BeatTracker
import com.music.bitchord.playback.smart.CurveWindow
import com.music.bitchord.playback.smart.LEVEL_FRAME_RATE
import com.music.bitchord.playback.smart.levelFromRms
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** A counted-frame window; no container seek timestamp is used as the PCM clock. */
internal class DesktopAnalysisPcm(val startFrame: Long, val rate: Int, val left: FloatArray, val right: FloatArray) {
    val start: Double get() = startFrame.toDouble() / rate
    val seconds: Double get() = left.size.toDouble() / rate
}

/** Reads monotonically ordered, possibly overlapping windows while retaining at most two windows. */
internal class DesktopAnalysisWindowReader(
    private val rate: Int,
    private val cancelled: () -> Boolean,
    private val nextBlock: () -> FloatArray?,
) {
    private var block = FloatArray(0)
    private var offset = 0
    private var frame = 0L
    private var previous: DesktopAnalysisPcm? = null
    private var ended = false

    fun read(from: Double, until: Double): DesktopAnalysisPcm? {
        if (cancelled() || ended && previous == null) return null
        val start = (from * rate).roundToLong()
        val end = (until * rate).roundToLong()
        require(start >= 0 && end >= start && end - start <= rate * 31L)
        val left = FloatArray((end - start).toInt())
        val right = FloatArray(left.size)
        var written = 0
        val old = previous
        previous = null
        if (start < frame) {
            require(old != null && start >= old.startFrame)
            val oldOffset = (start - old.startFrame).toInt()
            written = minOf(left.size, old.left.size - oldOffset).coerceAtLeast(0)
            old.left.copyInto(left, 0, oldOffset, oldOffset + written)
            old.right.copyInto(right, 0, oldOffset, oldOffset + written)
        }
        while (frame < end && !ended) {
            if (cancelled()) return null
            if (offset >= block.size) {
                val next = nextBlock()
                if (next == null) {
                    ended = true
                    break
                }
                block = next
                require(block.size % 2 == 0) { "Incomplete stereo frame" }
                offset = 0
                if (block.isEmpty()) continue
            }
            val available = (block.size - offset) / 2
            if (frame < start) {
                val skip = minOf(available.toLong(), start - frame).toInt()
                frame += skip
                offset += skip * 2
                continue
            }
            val count = minOf(available.toLong(), end - frame).toInt()
            for (i in 0 until count) {
                left[written + i] = block[offset + i * 2]
                right[written + i] = block[offset + i * 2 + 1]
            }
            written += count
            frame += count
            offset += count * 2
        }
        if (cancelled() || written == 0) return null
        return DesktopAnalysisPcm(start, rate,
            if (written == left.size) left else left.copyOf(written),
            if (written == right.size) right else right.copyOf(written),
        ).also { previous = it }
    }
}

internal object DesktopAnalysisWindows {
    const val OVERLAP = 1.0
    fun measuredDuration(frames: Int, rate: Double, reported: Double, container: Double? = null): Double? {
        val seconds = frames / rate
        if (!seconds.isFinite() || seconds <= 0) return null
        // Song durations can be rounded to whole seconds; container timing is more precise.
        if (seconds + 2.0 < reported) return null
        if (container != null && container.isFinite() && container > 0 && seconds + .25 < container) return null
        return seconds
    }

    fun headEnd(duration: Double, span: Double) = minOf(duration, 2 * span - OVERLAP)
    fun tailStart(duration: Double, span: Double) = maxOf(0.0, duration - (3 * span - 2 * OVERLAP))

    fun plan(duration: Double, span: Double): List<Pair<Double, Double>> {
        require(duration.isFinite() && duration > 0 && span > OVERLAP)
        val head = headEnd(duration, span)
        val tail = tailStart(duration, span)
        val spans = if (tail <= head) listOf(0.0 to duration) else listOf(0.0 to head, tail to duration)
        return spans.flatMap { (from, to) ->
            val length = to - from
            if (length < 1) emptyList() else if (length <= span) listOf(from to to) else {
                val count = ceil((length - OVERLAP) / (span - OVERLAP) - 1e-6).toInt()
                val stride = (length - span) / (count - 1)
                List(count) { i -> (from + i * stride) to minOf(to, from + i * stride + span) }
            }
        }
    }

    class Evidence(
        val start: Double,
        val seconds: Double,
        val grid: BeatTracker.Grid?,
        val vocal: CurveWindow? = null,
        val level: CurveWindow? = null,
    )

    fun side(regions: List<Evidence>, from: Double, until: Double): BeatSide? {
        val measured = regions.filter { it.grid != null && it.start < until && it.start + it.seconds > from }.sortedBy { it.start }
        val beats = ArrayList<Double>()
        val downbeats = ArrayList<Double>()
        var weighted = 0.0
        for ((i, region) in measured.withIndex()) {
            val previous = measured.getOrNull(i - 1)
            val next = measured.getOrNull(i + 1)
            val lower = maxOf(from, region.start, previous?.let { minOf(region.start + region.seconds, (it.start + it.seconds + region.start) / 2) } ?: from)
            val upper = minOf(until, region.start + region.seconds, next?.let { maxOf(region.start, (region.start + region.seconds + it.start) / 2) } ?: until)
            val kept = region.grid!!.beats.filter { it >= lower && it < upper }
            beats += kept
            downbeats += region.grid.downbeats.filter { it >= lower && it < upper }
            weighted += kept.size * region.grid.beatConfidence
        }
        val distinct = beats.distinct().sorted()
        val bpm = BeatTracker.tempoFromBeats(distinct)
        return if (bpm <= 0) null else BeatSide(distinct, downbeats.distinct().sorted(), bpm, if (beats.isEmpty()) 0.0 else weighted / beats.size)
    }

    fun levels(pcm: DesktopAnalysisPcm): CurveWindow? {
        val count = floor(pcm.seconds * LEVEL_FRAME_RATE + 1e-8).toInt()
        if (count == 0) return null
        val values = FloatArray(count) { index ->
            val from = (index * pcm.rate / LEVEL_FRAME_RATE).toInt()
            val until = ((index + 1) * pcm.rate / LEVEL_FRAME_RATE).toInt()
            var sum = 0.0
            for (i in from until until) {
                val mixed = (pcm.left[i] + pcm.right[i]) * .5
                sum += mixed * mixed
            }
            levelFromRms(sqrt(sum / (until - from))).toFloat()
        }
        return CurveWindow(pcm.start, LEVEL_FRAME_RATE, values)
    }

    fun overlay(times: List<Double>, fallback: List<Double>, windows: List<CurveWindow>): List<Double> =
        times.mapIndexed { index, time ->
            windows.lastOrNull { time >= it.startTime && time < it.endTime }?.let { window ->
                val frame = floor((time - window.startTime) * window.frameRate + 1e-8).toInt()
                window.values.getOrNull(frame)?.toDouble()
            } ?: fallback.getOrElse(index) { Double.NaN }
        }
}
