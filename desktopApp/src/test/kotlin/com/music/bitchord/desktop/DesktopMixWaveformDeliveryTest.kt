package com.music.bitchord.desktop

import com.music.bitchord.data.settings.MixDeck
import com.music.bitchord.data.settings.MixTransitionView
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopMixWaveformDeliveryTest {
    @Test fun replacementCancelsNativeHttpOpenBeforeItsLateResponseAndDeliversCurrentPcm() {
        val requested = CountDownLatch(1)
        val respond = CountDownLatch(1)
        val responded = CountDownLatch(1)
        val responseFailure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val lateMedia = wav(60.0)
        server.executor = executor
        server.createContext("/late.wav") { exchange ->
            try {
                requested.countDown()
                check(respond.await(20, TimeUnit.SECONDS)) { "test did not release late HTTP response" }
                // The client should already have cancelled. A broken pipe is expected in that case.
                runCatching {
                    exchange.responseHeaders.add("Content-Type", "audio/wav")
                    exchange.sendResponseHeaders(200, lateMedia.size.toLong())
                    exchange.responseBody.write(lateMedia)
                }
            } catch (failure: Throwable) {
                responseFailure.set(failure)
            } finally { exchange.close(); responded.countDown() }
        }
        server.start()
        val preparation = DesktopLoopMixPreparation()
        val files = ArrayList<Path>()
        val deliveries = CopyOnWriteArrayList<Long>()
        val currentView = AtomicReference<MixTransitionView?>()
        val currentReady = CountDownLatch(1)
        val ownership = DesktopTransitionOwnership()
        val first = ownership.select(1, "same-outgoing-id", "same-incoming-id")
        val replacement = ownership.select(2, "same-outgoing-id", "same-incoming-id")
        try {
            assertEquals(first.currentId, replacement.currentId)
            assertEquals(first.nextId, replacement.nextId)
            assertNotEquals(first.selection, replacement.selection)
            val oldRecording = assertNotNull(DesktopLoopMixRecording.capture(
                DesktopStream("http://127.0.0.1:${server.address.port}/late.wav")))
            val low = recording(60.0, files)
            val high = recording(4_000.0, files)
            preparation.select(first.selection)
            preparation.prepareWaves(first.selection, view(), oldRecording, oldRecording) {
                deliveries.add(first.selection)
            }
            assertTrue(requested.await(10, TimeUnit.SECONDS), "native decoder never requested the held response")
            preparation.select(replacement.selection)
            preparation.prepareWaves(replacement.selection, view(), low, high) {
                currentView.set(it)
                deliveries.add(replacement.selection)
                currentReady.countDown()
            }
            assertTrue(currentReady.await(5, TimeUnit.SECONDS),
                "cancelled native open must free the sole worker before the old server responds")
            assertEquals(1L, respond.count, "current work must finish while the old response remains held")
            assertMeasuredBands(assertNotNull(currentView.get()))
            respond.countDown()
            assertTrue(responded.await(5, TimeUnit.SECONDS), "late response handler did not terminate")
            assertEquals(null, responseFailure.get())
            assertEquals(listOf(replacement.selection), deliveries.toList(),
                "same catalogue IDs must not authorize obsolete waveform delivery")
        } finally {
            respond.countDown()
            preparation.close()
            server.stop(0)
            executor.shutdownNow()
            val preparationStopped = preparation.awaitTermination(TimeUnit.SECONDS.toNanos(10))
            val serverStopped = executor.awaitTermination(10, TimeUnit.SECONDS)
            files.forEach { Files.deleteIfExists(it) }
            assertTrue(preparationStopped, "wave preparation worker leaked")
            assertTrue(serverStopped, "HTTP fixture worker leaked")
        }
    }

    @Test fun completedNativeDecodeReturningAfterReplacementCannotPublishOldWaveforms() {
        val decoded = CountDownLatch(1)
        val finishOldRead = CountDownLatch(1)
        val barrierFailure = AtomicReference<Throwable?>()
        val checks = AtomicInteger()
        val files = ArrayList<Path>()
        val preparation = DesktopLoopMixPreparation()
        val deliveries = CopyOnWriteArrayList<Long>()
        val currentView = AtomicReference<MixTransitionView?>()
        val currentReady = CountDownLatch(1)
        val ownership = DesktopTransitionOwnership()
        val first = ownership.select(7, "same-outgoing-id", "same-incoming-id")
        val replacement = ownership.select(8, "same-outgoing-id", "same-incoming-id")
        try {
            val low = recording(60.0, files)
            val high = recording(4_000.0, files)
            preparation.select(first.selection)
            preparation.prepareWaves(first.selection, view(), low, high, obsolete = {
                // The second check follows a successful real native decode and recording recheck.
                if (checks.incrementAndGet() == 2) {
                    decoded.countDown()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                    var released = false
                    while (!released && System.nanoTime() < deadline) {
                        try { released = finishOldRead.await(
                            (deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                        } catch (_: InterruptedException) {
                            // Reproduce a native operation that returns after its cancellation signal.
                        }
                    }
                    if (!released) barrierFailure.set(AssertionError("late decode barrier timed out"))
                }
                false
            }) { deliveries.add(first.selection) }
            assertTrue(decoded.await(10, TimeUnit.SECONDS), "real native WAV decode did not reach its completion boundary")
            preparation.select(replacement.selection)
            preparation.prepareWaves(replacement.selection, view(), low, high) {
                currentView.set(it)
                deliveries.add(replacement.selection)
                currentReady.countDown()
            }
            assertEquals(1L, currentReady.count, "replacement must remain queued behind the returning old native owner")
            finishOldRead.countDown()
            assertTrue(currentReady.await(5, TimeUnit.SECONDS), "serialized current waveform work did not finish")
            assertEquals(null, barrierFailure.get())
            assertMeasuredBands(assertNotNull(currentView.get()))
            assertEquals(listOf(replacement.selection), deliveries.toList(),
                "a cancelled pass must not publish even when its decode finishes late with valid PCM")
        } finally {
            finishOldRead.countDown()
            preparation.close()
            val stopped = preparation.awaitTermination(TimeUnit.SECONDS.toNanos(10))
            files.forEach { Files.deleteIfExists(it) }
            assertTrue(stopped, "wave preparation worker leaked")
        }
    }

    private fun view(): MixTransitionView {
        fun deck(title: String) = MixDeck(title, "Generated PCM", null, 0.0, "", 3_000, 0.0, 1.0)
        return DesktopMixView.ordinary(deck("Outgoing"), deck("Incoming"), null, 0.0, 1.0, 0.0, 1.0)
    }

    private fun assertMeasuredBands(view: MixTransitionView) {
        assertEquals("Outgoing", view.outgoing.title)
        assertEquals("Incoming", view.incoming.title)
        assertEquals(512, view.outgoing.low.size)
        assertEquals(512, view.incoming.high.size)
        assertTrue(view.outgoing.low.max() > .2f, "generated bass PCM must reach the chart")
        assertTrue(view.outgoing.low.max() > view.outgoing.high.max() * 4)
        // At 4 kHz / 12 kHz, the chart's 2.5 kHz one-pole split gives a ~0.19 peak
        // from this 16000/32768 sine. A 0.2 minimum would reject the valid filter response.
        assertTrue(view.incoming.high.max() in .15f.. .20f,
            "generated treble PCM must reach the chart at its filtered level: ${view.incoming.high.max()}")
        assertTrue(view.incoming.high.max() > view.incoming.low.max() * 4)
        assertEquals(0.0, view.outgoing.waveStart)
        assertEquals(1.0, view.outgoing.waveEnd)
    }

    private fun recording(frequency: Double, files: MutableList<Path>): DesktopLoopMixRecording {
        val path = Files.createTempFile("bitchord-wave-delivery-", ".wav")
        files.add(path)
        Files.write(path, wav(frequency))
        return assertNotNull(DesktopLoopMixRecording.capture(DesktopStream(path.toString())))
    }

    private fun wav(frequency: Double): ByteArray {
        val rate = 12_000
        val frames = rate * 3
        return ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVEfmt ".toByteArray())
            putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
            put("data".toByteArray()).putInt(frames * 4)
            repeat(frames) { i ->
                val value = (16_000 * sin(2 * PI * frequency * i / rate)).toInt().toShort()
                putShort(value).putShort(value)
            }
        }.array()
    }
}
