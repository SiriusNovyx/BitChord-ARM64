package com.music.bitchord.desktop

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPreparationPolicyTest {
    private class ManualWorker : AbstractExecutorService() {
        val pending = ArrayDeque<Runnable>()
        private var closed = false
        override fun execute(command: Runnable) { check(!closed); pending.addLast(command) }
        override fun shutdown() { closed = true }
        override fun shutdownNow(): MutableList<Runnable> = pending.toMutableList().also { pending.clear(); closed = true }
        override fun isShutdown() = closed
        override fun isTerminated() = closed && pending.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }

    @Test fun `ten thousand next changes keep only current and final upcoming work`() {
        val worker = ManualWorker()
        val queue = DesktopPreparationQueue<String>(worker)
        val completed = mutableListOf<String>()
        try {
            repeat(10_000) { index ->
                queue.retain(listOf("current", "next-$index"))
                queue.submit("next-$index") { completed += "next-$index" }
                queue.submit("current") { completed += "current" }
            }
            assertEquals(1, worker.pending.size)
            worker.pending.removeFirst().run()
            assertEquals(listOf("current", "next-9999"), completed)
        } finally { queue.close() }
    }

    @Test fun `obsolete stalled pass is cancelled without overlap or waiting for it`() {
        val worker = Executors.newSingleThreadExecutor()
        val queue = DesktopPreparationQueue<String>(worker)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancellation = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val simultaneous = AtomicInteger()
        val peak = AtomicInteger()
        try {
            queue.retain(listOf("old"))
            queue.submit("old") { pass ->
                val count = simultaneous.incrementAndGet()
                peak.set(maxOf(peak.get(), count))
                pass.onCancel { cancellation.countDown() }
                entered.countDown()
                // Represents a native inference call that cannot stop until it returns.
                while (release.count != 0L) try { release.await() } catch (_: InterruptedException) { }
                assertTrue(pass.cancelled)
                simultaneous.decrementAndGet()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            queue.retain(listOf("new"))
            queue.submit("new") {
                assertEquals(1, simultaneous.incrementAndGet())
                assertFalse(Thread.currentThread().isInterrupted, "cancellation must not leak into the next pass")
                simultaneous.decrementAndGet()
                complete.countDown()
            }
            assertTrue(cancellation.await(2, TimeUnit.SECONDS))
            assertEquals(1L, complete.count, "a replacement cannot overlap uninterruptible native work")
            release.countDown()
            assertTrue(complete.await(2, TimeUnit.SECONDS))
            assertEquals(1, peak.get())
        } finally { release.countDown(); queue.close(); worker.awaitTermination(2, TimeUnit.SECONDS) }
    }

    @Test fun `same id ABA cannot revive an old pass or an accepted transition`() {
        val worker = ManualWorker()
        val queue = DesktopPreparationQueue<String>(worker)
        val completed = mutableListOf<String>()
        val owner = DesktopTransitionOwnership()
        val first = owner.select(1, "A", "B")
        assertTrue(owner.reserve(first))
        assertFalse(owner.reserve(first))
        owner.select(1, "A", "C")
        val latest = owner.select(1, "A", "B")
        assertFalse(owner.isCurrent(first))
        assertFalse(owner.consume(first))
        assertTrue(owner.reserve(latest))
        assertTrue(owner.consume(latest))
        assertFalse(owner.consume(latest))
        assertFalse(owner.reserve(latest))
        val reloaded = owner.select(2, "A", "B")
        assertFalse(owner.isCurrent(latest))
        assertTrue(owner.isCurrent(reloaded))
        try {
            queue.retain(listOf("B"))
            queue.submit("B") { completed += "old-B" }
            queue.retain(listOf("C"))
            queue.retain(listOf("B"))
            queue.submit("B") { completed += "new-B" }
            worker.pending.removeFirst().run()
            assertEquals(listOf("new-B"), completed)
        } finally { queue.close() }
    }

    @Test fun `cancelled pass cannot publish results or failures`() {
        val worker = ManualWorker()
        val queue = DesktopPreparationQueue<String>(worker)
        var writes = 0
        try {
            queue.retain(listOf("old"))
            queue.submit("old") { pass ->
                queue.retain(listOf("new"))
                assertFalse(pass.publish { writes++ })
            }
            worker.pending.removeFirst().run()
            assertEquals(0, writes)
        } finally { queue.close() }
    }

    @Test fun `close runs retirement once on the owning worker`() {
        val worker = ManualWorker()
        val queue = DesktopPreparationQueue<String>(worker)
        val events = mutableListOf<String>()
        queue.submit("active") { events += "obsolete work" }
        queue.close { events += "retired" }
        queue.close { events += "duplicate retirement" }
        assertTrue(worker.isShutdown)
        assertEquals(emptyList(), events, "close must not run native cleanup on its caller")
        while (worker.pending.isNotEmpty()) worker.pending.removeFirst().run()
        assertEquals(listOf("retired"), events)
    }
}
