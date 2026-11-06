package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.util.UUID
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAutomixSchedulingTest {
    private fun song(id: String) = Song(id, id, "Synthetic", null, durationText = "1:00")

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

    @Test fun `analysis requests share one dispatch instead of growing an executor backlog`() {
        assertTrue(DesktopAnalysisRuntime.available)
        val worker = ManualWorker()
        val analyzer = DesktopTrackAnalyzer(worker = worker)
        try {
            repeat(100) { analyzer.request(song("queue-${UUID.randomUUID()}"), DesktopStream("missing.wav"), 60.0) }
            assertEquals(1, worker.pending.size, "analysis dispatch must not grow with rapid next selection")
        } finally { analyzer.release() }
    }

    @Test fun `a pending skip cannot target a newly selected next track or overlap another skip`() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val prepared = CountDownLatch(1)
        val incomingQueued = CountDownLatch(1)
        val output = object : DesktopAudioOutput {
            override var format = DesktopPcmFormat(48_000, 2, 4, isFloat = true)
            override var gain = 1f
            override var isOpen = false
            override val deviceName = "Synthetic"
            override val bufferBytes = 0
            override fun isUsingSelection(id: String) = isOpen
            override fun open(requested: DesktopPcmFormat) = Result.success(requested).also { format = requested; isOpen = true }
            override fun write(samples: FloatArray, count: Int): Int {
                writing.countDown()
                release.await(3, TimeUnit.SECONDS)
                return count * format.bytesPerSample
            }
            override fun framesPlayed() = 0L
            override fun pause() = Unit
            override fun resume() = Unit
            override fun flush() = Unit
            override fun close() = Unit
        }
        val engine = DesktopPlaybackEngine({}, {}, output,
            { selected ->
                val pending = if (selected.videoId == "B") {
                    val delegate = CompletableDeferred<DesktopStream?>(null)
                    object : Deferred<DesktopStream?> by delegate {
                        override suspend fun await(): DesktopStream? { incomingQueued.countDown(); return null }
                    }
                } else null
                Result.success(DesktopLiveResolution(DesktopStream("synthetic.wav"), pending))
            },
            { selected, _, _ ->
                val source = object : DesktopSampleSource {
                    override var sampleCount = 960
                    override fun readSamples(): FloatArray { prepared.await(3, TimeUnit.SECONDS); return FloatArray(960) }
                    override fun seek(micros: Long) = true
                    override fun cancelPendingRead() { prepared.countDown() }
                    override fun close() = Unit
                }
                DesktopDecodeAhead(source, output.format, 60_000_000, null).also {
                    if (selected.videoId == "B") prepared.countDown()
                }
            }, false)
        try {
            engine.setSkipMixEnabled(true)
            engine.load(song("A"), false)
            await { !engine.state.value.isLoading }
            engine.prepareNext(song("B"))
            assertTrue(incomingQueued.await(3, TimeUnit.SECONDS))
            // A seek drains the incoming command even though playback is paused.
            engine.seekTo(100)
            await { engine.state.value.positionMs == 100L }
            engine.play()
            assertTrue(writing.await(3, TimeUnit.SECONDS))
            assertTrue(engine.beginSkipMix("B", 4))
            assertFalse(engine.beginSkipMix("B", 4), "the first accepted command reserves this handoff")
            engine.prepareNext(song("C"))
            assertFalse(engine.beginSkipMix("B", 4), "the old ready deck no longer owns the next selection")
            engine.prepareNext(song("B"))
            assertFalse(engine.beginSkipMix("B", 4), "same-ID reselection cannot revive the old ready deck")
            val seeks = engine.state.value.seeks
            engine.seekTo(200)
            release.countDown()
            await { engine.state.value.seeks >= seeks + 1 }
            assertEquals(200L, engine.state.value.positionMs, "state=${engine.state.value}")
            assertEquals(seeks + 1, engine.state.value.seeks,
                "the stale queued MixNow must not arm then abort a blend and cause an extra seek")
        } catch (failure: AssertionError) {
            val threads = Thread.getAllStackTraces().filterKeys { it.name.contains("BitChord") }
                .entries.joinToString("\n") { "${it.key.name}: ${it.value.take(8).joinToString()}" }
            throw AssertionError("${engine.state.value}\n$threads", failure)
        } finally { release.countDown(); prepared.countDown(); engine.shutdown() }
    }

    @Test fun `selection cancels stalled native decode and frees the worker for current work`() {
        assertTrue(DesktopAnalysisRuntime.available)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val latestAttempt = CountDownLatch(1)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/stalled") { exchange ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        val old = song("stalled-${UUID.randomUUID()}")
        val latest = song("latest-${UUID.randomUUID()}")
        val analyzer = DesktopTrackAnalyzer(sourceOverride = { selected ->
            if (selected.videoId == old.videoId) DesktopStream("http://127.0.0.1:${server.address.port}/stalled")
            else { latestAttempt.countDown(); null }
        })
        try {
            analyzer.selectPair(old.videoId, null)
            analyzer.request(old, null, 60.0)
            assertTrue(entered.await(20, TimeUnit.SECONDS),
                "native HTTP setup did not complete: ${analyzer.statusFor(old)}")
            analyzer.selectPair(latest.videoId, null)
            analyzer.request(latest, null, 60.0)
            assertTrue(latestAttempt.await(2, TimeUnit.SECONDS), "obsolete native open must yield before the server responds")
            assertFalse(analyzer.isAnalysed(old.videoId))
            assertEquals(com.music.bitchord.data.settings.TrackAnalysisState.WAITING, analyzer.statusFor(old).state)
        } finally { analyzer.release(); release.countDown(); server.stop(0) }
    }

    @Test fun `slow source replacement cannot leave obsolete queued analysis or publish stale failure`() {
        assertTrue(DesktopAnalysisRuntime.available)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val latestStarted = CountDownLatch(1)
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val current = song("current-${UUID.randomUUID()}")
        val old = song("old-${UUID.randomUUID()}")
        val latest = song("latest-${UUID.randomUUID()}")
        val analyzer = DesktopTrackAnalyzer(sourceOverride = { selected ->
            calls += selected.videoId
            if (selected.videoId == current.videoId) {
                entered.countDown()
                while (release.count != 0L) try { release.await() } catch (_: InterruptedException) { }
            } else if (selected.videoId == latest.videoId) latestStarted.countDown()
            null
        })
        try {
            analyzer.selectPair(current.videoId, old.videoId)
            analyzer.request(current, null, 60.0)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            analyzer.request(old, null, 60.0)
            analyzer.selectPair(latest.videoId, null)
            analyzer.request(latest, null, 60.0)
            assertEquals(listOf(current.videoId), calls.toList())
            release.countDown()
            assertTrue(latestStarted.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(current.videoId, latest.videoId), calls.toList())
            assertEquals(com.music.bitchord.data.settings.TrackAnalysisState.WAITING, analyzer.statusFor(current).state)
        } finally { release.countDown(); analyzer.release() }
    }

    @Test fun `same id reload cancels the old generation without imposing its retry cooldown`() {
        assertTrue(DesktopAnalysisRuntime.available)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replacement = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val selected = song("reload-${UUID.randomUUID()}")
        val analyzer = DesktopTrackAnalyzer(sourceOverride = {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                while (release.count != 0L) try { release.await() } catch (_: InterruptedException) { }
            } else replacement.countDown()
            null
        })
        try {
            analyzer.selectPair(selected.videoId, null, 1)
            analyzer.request(selected, null, 60.0)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            analyzer.selectPair(selected.videoId, null, 2)
            analyzer.request(selected, null, 60.0)
            release.countDown()
            assertTrue(replacement.await(3, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
        } finally { release.countDown(); analyzer.release() }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(predicate())
    }
}
