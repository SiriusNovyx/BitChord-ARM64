package com.music.bitchord.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class DesktopCanvasResourceTest {
    private class Resource : AutoCloseable {
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet() }
    }

    @Test fun `cancelling a blocking open closes the decoder before returning`() = runBlocking {
        val resource = Resource()
        val opening = CompletableDeferred<Unit>()
        val releaseOpen = CountDownLatch(1)
        val opened = CompletableDeferred<Unit>()
        var startedFrames = false
        val job = launch(Dispatchers.Default) {
            withDesktopCanvasResource(resource) {
                withContext(Dispatchers.IO) {
                    opening.complete(Unit)
                    check(releaseOpen.await(10, TimeUnit.SECONDS))
                    opened.complete(Unit)
                }
                startedFrames = true
            }
        }
        try {
            withTimeout(5_000) { opening.await() }
            job.cancel()
        } finally {
            releaseOpen.countDown()
            withTimeout(5_000) { job.join() }
        }
        assertTrue(opened.isCompleted, "The blocking native open returned after cancellation")
        assertFalse(startedFrames, "Cancellation must prevent handing the decoder to rendering")
        assertEquals(1, resource.closes.get())
    }

    @Test fun `cancelling frame playback closes the decoder once`() = runBlocking {
        val resource = Resource()
        val readingFrames = CompletableDeferred<Unit>()
        val job = launch {
            withDesktopCanvasResource(resource) {
                readingFrames.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(5_000) { readingFrames.await() }
        withTimeout(5_000) { job.cancelAndJoin() }
        assertEquals(1, resource.closes.get())
    }

    @Test fun `normal completion returns the result and closes the decoder`() = runBlocking {
        val resource = Resource()
        val result = withDesktopCanvasResource(resource) { "finished" }
        assertEquals("finished", result)
        assertEquals(1, resource.closes.get())
    }

    @Test fun `open failure is preserved while the decoder is closed`() = runBlocking {
        val resource = Resource()
        val failure = IllegalStateException("clip refused to open")
        val result = runCatching { withDesktopCanvasResource(resource) { throw failure } }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(1, resource.closes.get())
    }
}
