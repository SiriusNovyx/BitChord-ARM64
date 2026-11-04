package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.smart.AnalysisStore
import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.planTransition
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
import kotlin.test.assertTrue

class DesktopIncomingCuePreparationTest {
    private val pcm = DesktopPcmFormat(48_000, 2, 4, true)
    private val duration = 61.333

    private class Source(private val incoming: Boolean, private val silentLeadFrames: Long = 8L * 48_000,
        private val autoRead: Boolean = incoming) : DesktopSampleSource {
        val blocks = LinkedBlockingQueue<Int>()
        val seekEntered = CountDownLatch(1)
        val finishSeek = CountDownLatch(1)
        val cuedRead = CountDownLatch(1)
        val dataReady = CountDownLatch(1)
        val seeks = AtomicInteger()
        val target = AtomicLong(-1)
        private var position = 0L
        @Volatile private var cancelled = false
        override var sampleCount = 0
        override fun readSamples(): FloatArray? {
            val count = if (autoRead) 960 else blocks.take()
            if (cancelled || count <= 0) return FloatArray(0).also { sampleCount = 0 }
            if (incoming && target.get() >= 0) cuedRead.countDown()
            return FloatArray(count * 2) { index ->
                if (incoming && position + index / 2 >= silentLeadFrames) .2f else 0f
            }.also { sampleCount = it.size; position += count; dataReady.countDown() }
        }
        override fun prepareSeek() { cancelled = false }
        override fun seek(micros: Long): Boolean {
            seeks.incrementAndGet(); target.set(micros); position = micros * 48_000 / 1_000_000; seekEntered.countDown()
            assertTrue(finishSeek.await(5, TimeUnit.SECONDS), "Fixture seek was not released")
            return true
        }
        override fun cancelPendingRead() { cancelled = true; blocks.offer(0) }
        override fun close() { cancelled = true; blocks.offer(0) }
        fun emit(frames: Int) { blocks.offer(frames) }
    }

    private inner class Output : DesktopAudioOutput {
        override var format = pcm
        override var gain = 1f
        override var isOpen = false
        override val deviceName = "Deterministic slow-cue fixture"
        override val bufferBytes = 0
        val frames = AtomicLong()
        val firstBlend = AtomicLong(-1)
        @Volatile var firstBlendSong: String? = null
        lateinit var engine: DesktopPlaybackEngine
        override fun isUsingSelection(id: String) = isOpen
        override fun open(requested: DesktopPcmFormat) = Result.success(pcm).also { isOpen = true }
        override fun write(samples: FloatArray, count: Int): Int {
            val plan = DesktopPlaybackEngine::class.java.getDeclaredField("activePlan").apply { isAccessible = true }.get(engine)
            if (plan != null && firstBlend.get() < 0) {
                val track = DesktopPlaybackEngine::class.java.getDeclaredField("upcoming").apply { isAccessible = true }.get(engine)
                firstBlendSong = (track.javaClass.getDeclaredField("song").apply { isAccessible = true }.get(track) as Song).videoId
                firstBlend.compareAndSet(-1, frames.get())
            }
            frames.addAndGet(count.toLong() / 2)
            return count * 4
        }
        override fun framesPlayed() = frames.get()
        override fun pause() = Unit
        override fun resume() = Unit
        override fun flush() = Unit
        override fun close() { isOpen = false }
    }

    @Test fun `cue seek uses the available preparation window and is not repeated at onset`() = exercise()
    @Test fun `switching to a standard fade restores the intro before onset`() = exercise(standard = true)
    @Test fun `replacing a stalled cue cannot arm its obsolete queue entry`() = exercise(replace = true)
    @Test fun `same track reselection does not reuse an obsolete cue worker`() = exercise(replace = true, reselect = true)
    @Test fun `fade completion does not wait for incoming PCM beyond the fade boundary`() {
        val a = Song("end-A", "Generated outgoing", "Fixture", null, durationText = "1:01")
        val b = a.copy(videoId = "end-B")
        val output = Output()
        val outgoing = Source(false, 0, false)
        val incoming = Source(true, 0, false)
        val handoffs = AtomicInteger()
        val engine = DesktopPlaybackEngine({}, { handoffs.incrementAndGet() }, output,
            { Result.success(DesktopLiveResolution(DesktopStream("fixture://end", sourceId = "end-fixture"))) },
            { song, _, _ -> DesktopDecodeAhead(if (song.videoId == a.videoId) outgoing else incoming,
                pcm, 61_333_000, null, aheadSamples = 96_000) }, false)
        output.engine = engine
        try {
            outgoing.finishSeek.countDown()
            engine.setCrossfadeSeconds(4)
            engine.load(a, false)
            await("current ready") { !engine.state.value.isLoading && engine.state.value.song?.videoId == a.videoId }
            engine.prepareNext(b)
            val ready = DesktopPlaybackEngine::class.java.getDeclaredField("nextDeckOpenId").apply { isAccessible = true }
            await("next ready") { ready.get(engine) == b.videoId }
            val seeks = engine.state.value.seeks
            engine.seekTo(57_333)
            await("seek at planned start") { engine.state.value.seeks > seeks }
            assertTrue(outgoing.seekEntered.await(2, TimeUnit.SECONDS))
            incoming.emit(192_000)
            outgoing.emit(192_960)
            engine.play()
            assertTrue(incoming.dataReady.await(2, TimeUnit.SECONDS))
            assertTrue(outgoing.dataReady.await(2, TimeUnit.SECONDS))
            await("handoff with exactly the fade's incoming PCM") { handoffs.get() == 1 }
            assertEquals(192_000L, output.frames.get(), "The outgoing suffix after fade completion must not extend its ownership")
            assertEquals(b.videoId, engine.state.value.song?.videoId)
        } finally { outgoing.finishSeek.countDown(); incoming.finishSeek.countDown(); engine.shutdown() }
    }

    private fun exercise(standard: Boolean = false, replace: Boolean = false, reselect: Boolean = false) {
        val suffix = UUID.randomUUID().toString()
        val a = Song("cue-A-$suffix", "Generated outgoing", "Fixture", null, durationText = "1:01")
        val b = Song("cue-B-$suffix", "Generated incoming", "Fixture", null, durationText = "1:01")
        val c = b.copy(videoId = "cue-C-$suffix")
        val outgoing = TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = a.videoId,
            duration = duration, bpm = 120.0, contentEndTime = duration)
        val incoming = outgoing.copy(trackId = b.videoId, mixInTime = 8.0,
            audibleStartTime = 8.0, pickupTime = 8.0)
        val store = AnalysisStore { DesktopTrackAnalyzer.analysisCacheHome() }
        store.save(a.videoId, outgoing); store.save(b.videoId, incoming)
        if (replace) store.save(c.videoId, incoming.copy(trackId = c.videoId,
            mixInTime = 12.0, audibleStartTime = 12.0, pickupTime = 12.0))
        val planned = planTransition(outgoing, incoming, a.transitionInfo(61_333), b.transitionInfo(61_333),
            currentTime = 0.0, duration = duration, fadeSeconds = 4.0,
            mode = CrossfadeMode.SMART, advanced = true, mixWindow = true)
        assertEquals(8.0, planned.incomingCueTime, "The generated analysis must authorize the cue: $planned")
        assertTrue(planned.transitionStart > 30.0 && !planned.blocked)
        val output = Output()
        val sources = ConcurrentHashMap<String, Source>()
        val engine = DesktopPlaybackEngine({}, {}, output,
            { Result.success(DesktopLiveResolution(DesktopStream("fixture://cue", sourceId = "cue-fixture"))) },
            { song, _, _ ->
                val source = Source(song.videoId != a.videoId,
                    if (song.videoId == c.videoId) 12L * 48_000 else 8L * 48_000).also { sources[song.videoId] = it }
                DesktopDecodeAhead(source, pcm, 61_333_000, null, aheadSamples = 96_000)
            }, false)
        output.engine = engine
        try {
            engine.setCrossfadeSeconds(4)
            engine.setAutomixEnabled(true)
            engine.load(a, false)
            await("outgoing open") { !engine.state.value.isLoading && sources.containsKey(a.videoId) }
            engine.prepareNext(b)
            val ready = DesktopPlaybackEngine::class.java.getDeclaredField("nextDeckOpenId").apply { isAccessible = true }
            await("incoming open") { ready.get(engine) == b.videoId }
            val currentSource = sources.getValue(a.videoId)
            val nextSource = sources.getValue(b.videoId)
            currentSource.emit(960)
            engine.play()
            assertTrue(nextSource.seekEntered.await(2, TimeUnit.SECONDS),
                "The known cue must prepare before the planned transition, not seek for the first time at onset")
            assertTrue(output.frames.get() < 48_000)
            currentSource.emit(960)
            await("outgoing continues while incoming seek is stalled") { output.frames.get() >= 1_920 }
            assertEquals(-1L, output.firstBlend.get(), "Preparing a cue must not start the blend early")
            engine.pause()
            await("controls remain responsive during cue preparation") { !engine.state.value.isPlaying }
            assertFalse(engine.state.value.mixing)
            val selectedSource = if (replace) {
                engine.prepareNext(c)
                await("replacement is ready while the old seek remains stalled") { ready.get(engine) == c.videoId }
                if (reselect) {
                    engine.prepareNext(b)
                    await("new B context replaces C while old B is still stalled") {
                        ready.get(engine) == b.videoId && sources.getValue(b.videoId) !== nextSource
                    }
                }
                sources.getValue(if (reselect) b.videoId else c.videoId).also { it.finishSeek.countDown() }
            } else nextSource
            if (standard) engine.setAutomixEnabled(false)
            nextSource.finishSeek.countDown()
            if (!replace) assertTrue(nextSource.cuedRead.await(2, TimeUnit.SECONDS))
            currentSource.emit(960)
            engine.play()
            val expectedCue = if (standard) 0L else if (replace && !reselect) 12_000_000L else 8_000_000L
            val expectedSeeks = if (standard) 2 else 1
            await("selected plan's cue is prepared") {
                selectedSource.target.get() == expectedCue && selectedSource.seeks.get() == expectedSeeks
            }
            repeat(3_000) { currentSource.emit(960) }
            await("planned blend") { output.firstBlend.get() >= 0 }
            assertEquals(expectedSeeks, selectedSource.seeks.get(), "The ready cue must survive arming without another network seek")
            assertEquals(expectedCue, selectedSource.target.get())
            assertEquals(if (replace && !reselect) c.videoId else b.videoId, output.firstBlendSong)
            if (replace) assertEquals(1, nextSource.seeks.get())
            assertTrue(kotlin.math.abs(output.firstBlend.get() - planned.transitionStart * 48_000) <= 1)
        } finally {
            sources.values.forEach { it.finishSeek.countDown() }
            engine.shutdown()
        }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < limit) Thread.sleep(2)
        assertTrue(predicate(), message)
    }
}
