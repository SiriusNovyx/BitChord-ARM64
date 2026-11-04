package com.music.bitchord.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlaybackWorkerShutdownTest {
    private class Output : DesktopAudioOutput {
        override var format = DesktopPcmFormat(48_000, 2, 4, true)
        override var gain = 1f
        override var isOpen = false
        override val deviceName = "Shutdown fixture"
        override val bufferBytes = 0
        override fun isUsingSelection(id: String) = isOpen
        override fun open(requested: DesktopPcmFormat) = Result.success(requested)
        override fun write(samples: FloatArray, count: Int) = count * format.bytesPerSample
        override fun framesPlayed() = 0L
        override fun pause() = Unit
        override fun resume() = Unit
        override fun flush() = Unit
        override fun close() { isOpen = false }
    }

    @Test fun shutdownWaitsForLoopPreparationNativeCleanup() = heldWorker("loopPreparation", "queue", false)
    @Test fun shutdownWaitsForAnalysisNativeCleanup() = heldWorker("analyzer", "preparation", false)
    @Test fun stalledPreparationCannotExtendTheShutdownDeadline() = heldWorker("loopPreparation", "queue", true)

    @Suppress("UNCHECKED_CAST")
    private fun heldWorker(ownerName: String, queueName: String, expires: Boolean) {
        val engine = DesktopPlaybackEngine({}, {}, Output(), null, null, false)
        fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(owner)
        val queue = field(field(engine, ownerName), queueName) as DesktopPreparationQueue<Any>
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val returned = CountDownLatch(1)
        var stopper: Thread? = null
        try {
            assertTrue(queue.submit("held-native-cleanup") {
                entered.countDown()
                // Native calls may observe cancellation only when they return.
                try {
                    while (release.count != 0L) {
                        try { release.await() } catch (_: InterruptedException) { }
                    }
                } finally { cleaned.countDown() }
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            stopper = Thread {
                try { engine.shutdown(if (expires) 80 else 1_500) }
                finally { returned.countDown() }
            }.apply { start() }
            if (expires) {
                assertTrue(returned.await(1, TimeUnit.SECONDS), "stalled native work must respect the existing shutdown deadline")
                assertTrue(cleaned.count != 0L, "this fixture must still own its native cleanup")
            } else {
                assertFalse(returned.await(100, TimeUnit.MILLISECONDS), "shutdown must not leave preparation or analysis cleanup running")
                release.countDown()
                assertTrue(returned.await(2, TimeUnit.SECONDS))
                assertTrue(cleaned.count == 0L, "native cleanup must finish before shutdown returns")
            }
        } finally {
            release.countDown()
            engine.shutdown()
            stopper?.join(3_000)
            assertTrue(cleaned.await(2, TimeUnit.SECONDS))
        }
    }
}
