package com.music.bitchord.desktop

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class CanvasPlaybackRequest(
    val playing: Boolean,
    val transitionPaused: Boolean = false,
    val visible: Boolean = true,
    val resumeDelayMs: Long = 0,
)

internal suspend fun <T : Any> playDesktopCanvasFrames(
    requests: Flow<CanvasPlaybackRequest>,
    intervalMillis: () -> Long,
    decode: suspend () -> T?,
    present: (T) -> Unit,
    onResumed: () -> Unit,
    initialFramePresented: Boolean = false,
    waitMillis: suspend (Long) -> Unit = { delay(it) },
    nowNanos: () -> Long = System::nanoTime,
) {
    var transitionHeld = false
    var initialFramePending = initialFramePresented
    requests.distinctUntilChanged().collectLatest { request ->
        if (request.transitionPaused) transitionHeld = true
        if (!request.playing || !request.visible || request.transitionPaused) return@collectLatest
        if (transitionHeld && request.resumeDelayMs > 0) waitMillis(request.resumeDelayMs)
        transitionHeld = false
        if (initialFramePending) {
            waitMillis(intervalMillis().coerceAtLeast(1L))
            initialFramePending = false
        }
        var resumed = false
        while (true) {
            val started = nowNanos()
            val frame = decode() ?: break
            currentCoroutineContext().ensureActive()
            present(frame)
            if (!resumed) {
                onResumed()
                resumed = true
            }
            val spent = (nowNanos() - started).coerceAtLeast(0L) / 1_000_000L
            waitMillis((intervalMillis().coerceAtLeast(1L) - spent).coerceAtLeast(1L))
        }
    }
}
