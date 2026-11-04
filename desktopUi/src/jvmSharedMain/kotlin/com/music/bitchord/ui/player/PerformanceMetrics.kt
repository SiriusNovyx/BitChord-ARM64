package com.music.bitchord.ui.player

enum class PerformanceStage {
    BACKGROUND_FRAME_GAP, BACKGROUND_DRAW, BACKGROUND_LAYER_UPDATE, BACKGROUND_CROSSFADE_UPDATE, LYRICS_COMPOSE, LYRICS_FRAME, PLAYER_COMPOSE,
    IMAGE_READ, IMAGE_PREPARATION, TRACK_ANALYSIS,
}

data class PerformanceSample(
    val stage: PerformanceStage, val phase: String, val count: Long,
    val retained: Int, val meanNanos: Long, val p95Nanos: Long, val maxNanos: Long,
)

/** Optional bounded aggregates; never stores song identities, URLs or image data. */
class PerformanceMetrics(
    private val capacity: Int = 256,
    private val warmAfterNanos: Long = 120_000_000_000,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    init { require(capacity > 0); require(warmAfterNanos >= 0) }
    @Volatile var enabled: Boolean = false
        private set
    private var startedAt = 0L
    private var generation = 0L
    private var lastFrame: Long? = null
    private data class Samples(val durations: LongArray, var count: Long = 0, var sum: Long = 0, var max: Long = 0)
    private val samples = linkedMapOf<Pair<PerformanceStage, Boolean>, Samples>()

    @Synchronized fun setEnabled(value: Boolean) {
        if (enabled == value) return
        generation++
        enabled = value
        samples.clear()
        lastFrame = null
        if (value) startedAt = nowNanos()
    }

    @Synchronized fun reset() {
        generation++
        samples.clear()
        lastFrame = null
        if (enabled) startedAt = nowNanos()
    }

    class Measurement internal constructor(internal val startedAt: Long, internal val generation: Long)

    fun begin(): Measurement? {
        if (!enabled) return null
        return synchronized(this) { if (enabled) Measurement(nowNanos(), generation) else null }
    }
    @Synchronized fun elapsedNanos(): Long = if (enabled) (nowNanos() - startedAt).coerceAtLeast(0) else 0

    fun finish(stage: PerformanceStage, started: Measurement?) {
        if (started == null || !enabled) return
        synchronized(this) {
            if (enabled && started.generation == generation) {
                record(stage, (nowNanos() - started.startedAt).coerceAtLeast(0))
            }
        }
    }

    @Synchronized fun record(stage: PerformanceStage, durationNanos: Long = 0) {
        if (!enabled) return
        val warmed = nowNanos() - startedAt >= warmAfterNanos
        val bucket = samples.getOrPut(stage to warmed) { Samples(LongArray(capacity)) }
        val value = durationNanos.coerceAtLeast(0)
        bucket.durations[(bucket.count % capacity).toInt()] = value
        bucket.count++
        bucket.sum += value
        bucket.max = maxOf(bucket.max, value)
    }

    @Synchronized fun backgroundFrame(timestamp: Long) {
        if (!enabled) return
        val previous = lastFrame
        if (previous != null && timestamp > previous) record(PerformanceStage.BACKGROUND_FRAME_GAP, timestamp - previous)
        if (previous == null || timestamp > previous) lastFrame = timestamp
    }

    @Synchronized fun pauseFrames() { lastFrame = null }

    @Synchronized fun snapshot(): List<PerformanceSample> = samples.map { (key, bucket) ->
        val retained = minOf(bucket.count, capacity.toLong()).toInt()
        val recent = bucket.durations.take(retained).sorted()
        PerformanceSample(key.first, if (key.second) "Warmed" else "Startup", bucket.count,
            retained, bucket.sum / bucket.count, recent[((retained - 1) * .95).toInt()], bucket.max)
    }
}

object DesktopVisualMetrics {
    val collector = PerformanceMetrics()
}
