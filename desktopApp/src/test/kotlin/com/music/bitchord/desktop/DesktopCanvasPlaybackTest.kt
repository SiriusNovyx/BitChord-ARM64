package com.music.bitchord.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopCanvasPlaybackTest {
    private class Waits {
        val calls = Channel<Pair<Long, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
        suspend fun wait(ms: Long) {
            val release = CompletableDeferred<Unit>()
            calls.send(ms to release)
            release.await()
        }
    }

    @Test(timeout = 10_000) fun matchingInitialFrameIsPacedBeforeTheClipAdvances() = runBlocking {
        val requests = MutableStateFlow(CanvasPlaybackRequest(playing = true))
        val waits = Waits()
        var decoded = 0
        var resumed = 0
        val job = launch {
            playDesktopCanvasFrames(requests, intervalMillis = { 125 }, decode = { ++decoded },
                present = {}, onResumed = { resumed++ }, initialFramePresented = true,
                waitMillis = waits::wait, nowNanos = { 0L })
        }
        val initialWait = waits.calls.receive()
        assertEquals(125L, initialWait.first)
        assertEquals(0, decoded)
        assertEquals(0, resumed)
        initialWait.second.complete(Unit)
        val nextWait = waits.calls.receive()
        assertEquals(125L, nextWait.first)
        assertEquals(1, decoded)
        assertEquals(1, resumed)
        job.cancelAndJoin()
    }

    @Test(timeout = 10_000) fun resumeWaitIsCancellableAndDoesNotConsumeFrames() = runBlocking {
        val requests = MutableStateFlow(CanvasPlaybackRequest(playing = true, transitionPaused = true, resumeDelayMs = 1_000))
        val waits = Waits()
        var decoded = 0
        var presented = 0
        var resumed = 0
        val job = launch {
            playDesktopCanvasFrames(requests, intervalMillis = { 125 },
                decode = { ++decoded }, present = { presented++ }, onResumed = { resumed++ }, waitMillis = waits::wait,
                nowNanos = { 0L })
        }
        repeat(3) { yield() }
        assertEquals(0, decoded)
        requests.value = requests.value.copy(transitionPaused = false)
        val firstWait = waits.calls.receive()
        assertEquals(1_000L, firstWait.first)
        assertEquals(0, decoded)
        requests.value = requests.value.copy(transitionPaused = true)
        repeat(3) { yield() }
        firstWait.second.complete(Unit)
        repeat(3) { yield() }
        assertEquals(0, decoded)
        requests.value = requests.value.copy(transitionPaused = false)
        val resumedWait = waits.calls.receive()
        assertEquals(1_000L, resumedWait.first)
        resumedWait.second.complete(Unit)
        val frameWait = waits.calls.receive()
        assertEquals(125L, frameWait.first)
        assertEquals(1, decoded)
        assertEquals(1, presented)
        assertEquals(1, resumed)
        requests.value = requests.value.copy(playing = false)
        repeat(3) { yield() }
        frameWait.second.complete(Unit)
        repeat(3) { yield() }
        assertEquals(1, decoded)
        job.cancelAndJoin()
    }

    @Test(timeout = 10_000) fun obsoleteBlockingDecodeCannotPresentAfterPauseOrDisposal() = runBlocking {
        val requests = MutableStateFlow(CanvasPlaybackRequest(playing = true))
        val entered = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Int>()
        var presented = false
        val job = launch {
            playDesktopCanvasFrames(requests, intervalMillis = { 125 },
                decode = { entered.complete(Unit); blocked.await() },
                present = { presented = true }, onResumed = {})
        }
        entered.await()
        assertTrue(entered.isCompleted)
        requests.value = requests.value.copy(transitionPaused = true)
        repeat(3) { yield() }
        blocked.complete(1)
        repeat(3) { yield() }
        assertFalse(presented)
        job.cancelAndJoin()
        assertFalse(presented)
    }

    @Test(timeout = 10_000) fun visiblePlaybackIsFramePacedAndHiddenPlaybackDoesNoWork() = runBlocking {
        val requests = MutableStateFlow(CanvasPlaybackRequest(playing = true))
        val waits = Waits()
        var frames = 0
        val job = launch {
            playDesktopCanvasFrames(requests, intervalMillis = { 125 }, decode = { ++frames },
                present = {}, onResumed = {}, waitMillis = waits::wait, nowNanos = { 0L })
        }
        var frameWait = waits.calls.receive()
        assertEquals(125L, frameWait.first)
        assertEquals(1, frames)
        repeat(8) {
            frameWait.second.complete(Unit)
            frameWait = waits.calls.receive()
            assertEquals(125L, frameWait.first)
        }
        assertEquals(9, frames)
        requests.value = requests.value.copy(visible = false)
        repeat(3) { yield() }
        frameWait.second.complete(Unit)
        repeat(3) { yield() }
        assertEquals(9, frames)
        job.cancelAndJoin()
    }
}
