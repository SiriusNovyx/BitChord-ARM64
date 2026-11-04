package com.music.bitchord.ui.player

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Installed only in background animation effects, never in the application's or lyrics' clock. */
internal class BackgroundFrameClock(
    private val upstream: MonotonicFrameClock,
    private val cadence: StateFlow<Int>,
    private val nowNanos: () -> Long = System::nanoTime,
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
) : MonotonicFrameClock {
    private var nextFrameAt: Long? = null
    private var previousRate: Int? = null

    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
        while (true) {
            if (cadence.value <= 0) {
                nextFrameAt = null
                DesktopVisualMetrics.collector.pauseFrames()
            }
            val fps = cadence.first { it > 0 }
            if (previousRate != fps) { nextFrameAt = null; previousRate = fps }
            val period = 1_000_000_000L / fps
            val remaining = nextFrameAt?.let { it - nowNanos() } ?: 0L
            // Wake before the target pulse; rounding up would miss it and add a whole vsync.
            if (remaining >= 1_000_000) waitMillis(remaining / 1_000_000)
            if (cadence.value <= 0) continue
            return upstream.withFrameNanos { frame ->
                val target = nextFrameAt
                // Compose's desktop frame clock can start at zero; deadlines share the wait clock.
                val observed = nowNanos()
                nextFrameAt = if (target == null || observed - target > period) observed + period else target + period
                DesktopVisualMetrics.collector.backgroundFrame(frame)
                onFrame(frame)
            }
        }
    }
}

internal suspend fun <T> withBackgroundFramePacing(block: suspend CoroutineScope.() -> T): T {
    val upstream = currentCoroutineContext()[MonotonicFrameClock] ?: return kotlinx.coroutines.coroutineScope(block)
    return withContext(BackgroundFrameClock(upstream, PlayerSettings.backgroundFramesPerSecond), block)
}
