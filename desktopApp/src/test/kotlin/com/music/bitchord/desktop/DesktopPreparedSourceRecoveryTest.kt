package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.smart.AnalysisStore
import com.music.bitchord.playback.smart.TrackAnalysis
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopPreparedSourceRecoveryTest {
    private val pcm = DesktopPcmFormat(48_000, 2, 4, true)
    private val nextId = DesktopPlaybackEngine::class.java.getDeclaredField("nextDeckOpenId").apply { isAccessible = true }
    private val nextTrack = DesktopPlaybackEngine::class.java.getDeclaredField("upcoming").apply { isAccessible = true }

    private class Source(private val incoming: Boolean, private val cueFailure: Exception?) : DesktopSampleSource {
        private val blocks = LinkedBlockingQueue<Int>()
        private var position = 0L
        @Volatile private var cancelled = false
        val cueEntered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override var sampleCount = 0
        override fun readSamples(): FloatArray? {
            val frames = if (incoming) 960 else blocks.take()
            if (cancelled || frames <= 0) return FloatArray(0).also { sampleCount = 0 }
            return FloatArray(frames * 2) { sample ->
                if (!incoming) .1f else if (position + sample / 2 >= 8L * 48_000) .2f else 0f
            }.also { position += frames; sampleCount = it.size }
        }
        override fun prepareSeek() { cancelled = false }
        override fun seek(micros: Long): Boolean {
            position = micros * 48_000 / 1_000_000
            cueEntered.countDown()
            if (cueFailure != null) throw cueFailure
            return true
        }
        override fun cancelPendingRead() { cancelled = true; blocks.offer(0) }
        override fun close() { cancelled = true; blocks.offer(0); closed.countDown() }
        fun emit(frames: Int) { blocks.offer(frames) }
    }

    private inner class Output : DesktopAudioOutput {
        override var format = pcm
        override var gain = 1f
        override var isOpen = false
        override val deviceName = "Prepared source recovery fixture"
        override val bufferBytes = 0
        val frames = AtomicLong()
        val flushes = AtomicInteger()
        @Volatile var beforeWrite: (() -> Unit)? = null
        override fun isUsingSelection(id: String) = isOpen
        override fun open(requested: DesktopPcmFormat) = Result.success(pcm).also { isOpen = true }
        override fun write(samples: FloatArray, count: Int): Int {
            beforeWrite?.invoke()
            frames.addAndGet(count.toLong() / 2)
            return count * 4
        }
        override fun framesPlayed() = frames.get()
        override fun pause() = Unit
        override fun resume() = Unit
        override fun flush() { flushes.incrementAndGet() }
        override fun close() { isOpen = false }
    }

    private inner class Harness(
        private val everyAttemptFails: Boolean = false,
        private val blockRetry: Boolean = false,
        private val failure: Exception = IOException("Synthetic cue transport failure"),
        private val remote: Boolean = true,
        private val pendingUpgrade: Boolean = false,
    ) {
        private val session = UUID.randomUUID().toString()
        val a = Song("recover-A-$session", "Generated outgoing", "Fixture", null, durationText = "1:01")
        val b = a.copy(videoId = "recover-B-$session", title = "Generated incoming")
        val c = b.copy(videoId = "recover-C-$session")
        val resolutions = ConcurrentHashMap<String, AtomicInteger>()
        val opens = ConcurrentHashMap<String, AtomicInteger>()
        val sources = ConcurrentHashMap<String, Source>()
        val readers = ConcurrentHashMap<String, DesktopDecodeAhead>()
        val output = Output()
        val retryEntered = CountDownLatch(1)
        val finishRetry = CountDownLatch(1)
        val handoffs = AtomicInteger()
        val ended = AtomicInteger()
        val outstandingUpgrade = CompletableDeferred<DesktopStream?>()
        val engine: DesktopPlaybackEngine
        var initialFlushes = 0

        init {
            val store = AnalysisStore { DesktopTrackAnalyzer.analysisCacheHome() }
            val analysis = TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = a.videoId,
                duration = 61.333, bpm = 120.0, contentEndTime = 61.333)
            store.save(a.videoId, analysis)
            for (song in listOf(b, c)) store.save(song.videoId, analysis.copy(trackId = song.videoId,
                mixInTime = 8.0, audibleStartTime = 8.0, pickupTime = 8.0))
            engine = DesktopPlaybackEngine({ ended.incrementAndGet() }, { handoffs.incrementAndGet() }, output,
                { song ->
                    val attempt = resolutions.computeIfAbsent(song.videoId) { AtomicInteger() }.incrementAndGet()
                    val url = if (remote) "https://fixture.invalid/${song.videoId}" else "C:/fixture/${song.videoId}.wav"
                    Result.success(DesktopLiveResolution(DesktopStream(url, sourceId = "recovery-fixture"),
                        pendingSubstitute = outstandingUpgrade.takeIf { pendingUpgrade && song.videoId == b.videoId && attempt == 1 }))
                },
                { song, _, _ ->
                    val attempt = opens.computeIfAbsent(song.videoId) { AtomicInteger() }.incrementAndGet()
                    if (song.videoId == b.videoId && attempt == 2 && blockRetry) {
                        retryEntered.countDown()
                        assertTrue(finishRetry.await(5, TimeUnit.SECONDS), "Fixture recovery open was not released")
                    }
                    val key = "${song.videoId}#$attempt"
                    val fail = if (song.videoId == b.videoId && (attempt == 1 || everyAttemptFails)) failure else null
                    val source = Source(song.videoId != a.videoId, fail).also { sources[key] = it }
                    DesktopDecodeAhead(source, pcm, 61_333_000L, null, aheadSamples = 96_000).also { readers[key] = it }
                }, false)
        }

        fun start() {
            engine.setCrossfadeSeconds(4)
            engine.setAutomixEnabled(true)
            engine.load(a, false)
            await("outgoing ready") { !engine.state.value.isLoading && source(a, 1) != null }
            initialFlushes = output.flushes.get()
            engine.prepareNext(b)
            await("initial next deck ready") { nextId.get(engine) == b.videoId }
            source(a, 1)!!.emit(960)
            engine.play()
            assertTrue(source(b, 1)!!.cueEntered.await(2, TimeUnit.SECONDS), "Initial cue must be attempted well before the fade")
            source(a, 1)!!.emit(960)
            await("outgoing prefix played") { output.frames.get() >= 1920L }
        }

        fun source(song: Song, attempt: Int) = sources["${song.videoId}#$attempt"]
        fun reader(song: Song, attempt: Int) = readers["${song.videoId}#$attempt"]
        fun preparedReader(): Any? = nextTrack.get(engine)?.let { track ->
            track.javaClass.getDeclaredField("decoder").apply { isAccessible = true }.get(track)
        }
        fun count(map: ConcurrentHashMap<String, AtomicInteger>, song: Song) = map[song.videoId]?.get() ?: 0
        fun assertOutgoingUninterrupted() {
            assertEquals(1, count(opens, a), "Healthy outgoing decoder must not be reopened")
            assertEquals(1, count(resolutions, a), "Healthy outgoing source must not be re-resolved")
            assertEquals(initialFlushes, output.flushes.get(), "Prepared-deck failure must not flush current audio")
            assertEquals(1L, source(a, 1)!!.closed.count)
            assertEquals(a.videoId, engine.state.value.song?.videoId)
            assertEquals(null, engine.state.value.error)
            assertEquals(0, handoffs.get())
            assertEquals(0, ended.get())
        }
        fun close() { outstandingUpgrade.cancel(); finishRetry.countDown(); engine.shutdown() }
    }

    @Test fun `failed cue preparation refreshes the next source before the fade without reopening current`() {
        val h = Harness()
        try {
            h.start()
            await("next source refreshed while preparation time remains") { h.count(h.opens, h.b) == 2 && nextId.get(h.engine) == h.b.videoId }
            h.source(h.a, 1)!!.emit(960)
            assertTrue(h.source(h.b, 2)!!.cueEntered.await(2, TimeUnit.SECONDS))
            assertTrue(h.source(h.b, 1)!!.closed.await(1, TimeUnit.SECONDS))
            h.assertOutgoingUninterrupted()
            repeat(3200) { h.source(h.a, 1)!!.emit(960) }
            await("automatic handoff uses the recovered deck") { h.handoffs.get() == 1 }
            assertEquals(h.b.videoId, h.engine.state.value.song?.videoId)
            assertEquals(1, h.count(h.opens, h.a))
            assertEquals(null, h.engine.state.value.error)
        } finally { h.close() }
    }

    @Test fun `a second failed preparation retires the next deck without a retry loop or current interruption`() {
        val h = Harness(everyAttemptFails = true)
        try {
            h.start()
            await("one next-deck retry") { h.count(h.opens, h.b) == 2 && nextId.get(h.engine) == h.b.videoId }
            repeat(20) { h.source(h.a, 1)!!.emit(960) }
            assertTrue(h.source(h.b, 2)!!.cueEntered.await(2, TimeUnit.SECONDS))
            await("failed retry is no longer advertised as ready") { nextId.get(h.engine) == null }
            assertTrue(h.source(h.b, 2)!!.closed.await(1, TimeUnit.SECONDS))
            repeat(20) { h.source(h.a, 1)!!.emit(960) }
            await("outgoing continues after the failed retry") { h.output.frames.get() >= 30_000 }
            assertEquals(2, h.count(h.opens, h.b))
            assertEquals(2, h.count(h.resolutions, h.b))
            h.assertOutgoingUninterrupted()
        } finally { h.close() }
    }

    @Test fun `a late recovery open cannot replace a newer request for the same next song`() {
        val h = Harness(blockRetry = true)
        try {
            h.start()
            assertTrue(h.retryEntered.await(2, TimeUnit.SECONDS), "Recovery must open before the fade")
            h.engine.prepareNext(h.c)
            await("C selected during old B recovery") { nextId.get(h.engine) == h.c.videoId }
            h.engine.prepareNext(h.b)
            await("new B context ready") { h.reader(h.b, 3) != null && h.preparedReader() === h.reader(h.b, 3) }
            h.finishRetry.countDown()
            await("late old B reader returned") { h.source(h.b, 2) != null }
            assertTrue(h.source(h.b, 2)!!.closed.await(2, TimeUnit.SECONDS))
            assertSame(h.reader(h.b, 3), h.preparedReader())
            assertEquals(1L, h.source(h.b, 3)!!.closed.count)
            h.assertOutgoingUninterrupted()
        } finally { h.close() }
    }

    @Test fun `pause remains in effect when a prepared source recovery completes`() {
        val h = Harness(blockRetry = true)
        try {
            h.start()
            assertTrue(h.retryEntered.await(2, TimeUnit.SECONDS))
            h.engine.pause()
            await("paused during recovery") { !h.engine.state.value.isPlaying }
            val frames = h.output.frames.get()
            h.finishRetry.countDown()
            await("recovered next deck installed") { h.reader(h.b, 2) != null && h.preparedReader() === h.reader(h.b, 2) }
            h.source(h.a, 1)!!.emit(4800)
            Thread.sleep(80)
            assertFalse(h.engine.state.value.isPlaying)
            assertEquals(frames, h.output.frames.get())
            h.assertOutgoingUninterrupted()
            h.engine.play()
            await("explicit resume advances outgoing audio") { h.output.frames.get() > frames }
        } finally { h.close() }
    }

    @Test fun `non transport and local preparation failures retire without network retries`() {
        for ((failure, remote) in listOf(IllegalStateException("Synthetic media failure") to true, IOException("Local seek failed") to false)) {
            val h = Harness(failure = failure, remote = remote)
            try {
                h.start()
                await("unusable prepared deck retired") { nextId.get(h.engine) == null }
                assertTrue(h.source(h.b, 1)!!.closed.await(1, TimeUnit.SECONDS))
                h.source(h.a, 1)!!.emit(4800)
                await("current audio continues") { h.output.frames.get() >= 6720 }
                assertEquals(1, h.count(h.opens, h.b))
                h.assertOutgoingUninterrupted()
            } finally { h.close() }
        }
    }

    @Test fun cannotReserveKnownFailedDeckDuringABlockedCurrentWrite() {
        val h = Harness()
        val writeEntered = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        try {
            h.engine.setCrossfadeSeconds(4)
            h.engine.setAutomixEnabled(true)
            h.engine.load(h.a, false)
            await("outgoing ready") { !h.engine.state.value.isLoading && h.source(h.a, 1) != null }
            h.engine.prepareNext(h.b)
            await("next deck ready") { nextId.get(h.engine) == h.b.videoId }
            h.output.beforeWrite = {
                writeEntered.countDown()
                assertTrue(finishWrite.await(5, TimeUnit.SECONDS), "Fixture output was not released")
            }
            h.source(h.a, 1)!!.emit(960)
            h.engine.play()
            assertTrue(writeEntered.await(2, TimeUnit.SECONDS))
            await("next-deck failure published while the pump is in output") {
                h.reader(h.b, 1)!!.poll(0) is DesktopDecodedRead.Failed
            }
            assertFalse(h.engine.beginSkipMix(h.b.videoId, 4), "A failed prepared deck must not reserve a manual transition")
        } finally { finishWrite.countDown(); h.close() }
    }

    @Test fun `the failed deck's outstanding source upgrade is cancelled before recovery`() {
        val h = Harness(pendingUpgrade = true)
        try {
            h.start()
            await("selected next source recovered") { h.count(h.opens, h.b) == 2 }
            assertTrue(h.outstandingUpgrade.isCancelled, "Obsolete source lookup must not outlive its failed prepared deck")
            h.assertOutgoingUninterrupted()
        } finally { h.close() }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < limit) Thread.sleep(2)
        assertTrue(condition(), message)
    }
}
