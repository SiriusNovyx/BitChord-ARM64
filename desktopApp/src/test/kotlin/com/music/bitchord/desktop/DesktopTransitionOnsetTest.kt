package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.smart.TransitionPlan
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopTransitionOnsetTest {
    @Test fun `automatic onset reaches the planned sample without an output queue`() = assertOnset()
    @Test fun `one hundred milliseconds queued does not postpone the blend`() = assertOnset(queuedFrames = 4_800)
    @Test fun `two hundred fifty milliseconds queued does not postpone the blend`() = assertOnset(queuedFrames = 12_000)
    @Test fun `a non-quarter-second boundary splits a large decoded block`() = assertOnset(blockFrames = 4_096)
    @Test fun `a prime decoder block size retains the planned boundary`() = assertOnset(queuedFrames = 4_800, blockFrames = 127)
    @Test fun `short output writes cannot move the planned boundary`() = assertOnset(queuedFrames = 12_000, shortWrites = true)
    @Test fun `a seek rebases rendering without rebasing the song plan`() = assertOnset(startMs = 209)
    @Test fun `conversion into the device sample clock retains the planned boundary`() = assertOnset(sourceRate = 44_100)
    @Test fun `three quarter listener speed preserves the planned PCM onset through stretching`() = assertListenerSpeed(.75f)
    @Test fun `faster listener speed preserves the planned PCM onset through stretching`() = assertListenerSpeed(1.25f)
    @Test fun `a transient held by the listener stretcher survives deck promotion`() {
        val reference = assertNotNull(capture(record = true, marker = true, seconds = 13.0).samples)
        val actual = assertNotNull(capture(speed = 1.25f, record = true, marker = true).samples)
        val expected = FloatArray(actual.size)
        val stretcher = DesktopAudioSpeed(2, pcm.sampleRate).apply { speed = 1.25f }
        var input = 0
        var output = 0
        while (input < reference.size && output < expected.size) {
            val count = minOf(8_192, reference.size - input)
            val rendered = stretcher.process(reference.copyOfRange(input, input + count), count)
            val copied = minOf(stretcher.outputCount, expected.size - output)
            rendered.copyInto(expected, output, 0, copied)
            output += copied
            input += count
        }
        assertEquals(expected.size, output)
        var changed = 0
        var first = -1
        for (index in expected.indices) if (abs(expected[index] - actual[index]) > .00001f) {
            changed++; if (first < 0) first = index / 2
        }
        assertEquals(0, changed,
            "Promotion must retain the continuously stretched signal, including its transient: first changed frame=$first")
    }
    @Test fun `the promoted source clock follows heard PCM while stretch input is still retained`() {
        for (queue in listOf(0L, 12_000L)) {
            val actual = capture(queuedFrames = queue, speed = 1.25f, record = true, ramp = true)
            val samples = assertNotNull(actual.samples)
            val value = samples[((actual.heardFrames - 1) * 2).toInt()]
            val sourceUsFromPcm = ((value.toDouble() - .1) / .0048 * 1_000_000).toLong()
            assertTrue(abs(actual.sourceClockUs - sourceUsFromPcm) < 20_000,
                "queued=$queue: clock=${actual.sourceClockUs}us, encoded heard PCM=${sourceUsFromPcm}us")
        }
    }

    private val pcm = DesktopPcmFormat(48_000, 2, 4, true)
    private val durationUs = 10_333_000L

    private inner class Source(private val incoming: Boolean, private val rate: Int,
        private val blockFrames: Int, startUs: Long, private val marker: Boolean,
        private val ramp: Boolean) : DesktopSampleSource {
        private var next = startUs * rate / 1_000_000
        @Volatile private var closed = false
        override var sampleCount = 0
        override fun readSamples(): FloatArray? {
            val remaining = durationUs * rate / 1_000_000 - next
            if (closed || remaining <= 0) return null
            val frames = minOf(blockFrames.toLong(), remaining).toInt()
            return FloatArray(frames * 2) { index ->
                if (!incoming) 0f else if (marker && next + index / 2 in 191_520L until 191_760L) -.2f
                else if (ramp) (.1 + (next + index / 2).toDouble() / rate * .0048).toFloat() else .2f
            }.also { sampleCount = it.size; next += frames }
        }
        override fun prepareSeek() { closed = false }
        override fun seek(micros: Long): Boolean { next = micros * rate / 1_000_000; closed = false; return true }
        override fun cancelPendingRead() { closed = true }
        override fun close() { closed = true }
    }

    private inner class Output(private val queuedFrames: Long, private val shortWrites: Boolean,
        val targetFrames: Long, record: Boolean) : DesktopAudioOutput {
        override var format = pcm
        override var gain = 1f
        override var isOpen = false
        override val deviceName = "Deterministic onset PCM capture"
        override val bufferBytes = (queuedFrames * pcm.frameBytes).toInt()
        val submitted = AtomicLong()
        val firstNonzero = AtomicLong(-1)
        val captured = if (record) FloatArray(targetFrames.toInt() * 2) else null
        @Volatile var firstBlendFrame = -1L
        @Volatile var firstPlan: TransitionPlan? = null
        lateinit var engine: DesktopPlaybackEngine
        override fun isUsingSelection(id: String) = isOpen
        override fun open(requested: DesktopPcmFormat) = Result.success(pcm).also { isOpen = true }
        override fun write(samples: FloatArray, count: Int): Int {
            val available = minOf(count.toLong(), (targetFrames - submitted.get()).coerceAtLeast(0) * 2).toInt()
            val accepted = if (shortWrites) minOf(available, 14) else available
            if (accepted == 0) return 0
            captured?.let { System.arraycopy(samples, 0, it, submitted.get().toInt() * 2, accepted) }
            if (firstBlendFrame < 0) {
                val plan = DesktopPlaybackEngine::class.java.getDeclaredField("activePlan")
                    .apply { isAccessible = true }.get(engine) as TransitionPlan?
                if (plan != null) { firstPlan = plan; firstBlendFrame = submitted.get() }
            }
            for (index in 0 until accepted step 2) {
                if (abs(samples[index]) > .0000001f || abs(samples[index + 1]) > .0000001f)
                    firstNonzero.compareAndSet(-1, submitted.get() + index / 2)
            }
            submitted.addAndGet(accepted.toLong() / 2)
            return accepted * 4
        }
        override fun framesPlayed() = (submitted.get() - queuedFrames).coerceAtLeast(0)
        override fun pause() = Unit
        override fun resume() = Unit
        override fun flush() = Unit
        override fun close() { isOpen = false }
    }

    private data class Capture(val plan: TransitionPlan, val firstBlend: Long, val firstNonzero: Long,
        val samples: FloatArray?, val heardFrames: Long, val sourceClockUs: Long)

    private fun assertOnset(queuedFrames: Long = 0, blockFrames: Int = 960,
        sourceRate: Int = 48_000, shortWrites: Boolean = false, startMs: Long = 0) {
        val captured = capture(queuedFrames, blockFrames, sourceRate, shortWrites, startMs)
        val expectedFrame = ((captured.plan.transitionStart - startMs / 1_000.0) * pcm.sampleRate).roundToLong()
        assertTrue(abs(captured.firstBlend - expectedFrame) <= 1,
            "queued=$queuedFrames block=$blockFrames: planned=$expectedFrame, actual=${captured.firstBlend}")
        assertTrue(captured.firstNonzero in captured.firstBlend + 1..captured.firstBlend + 64,
            "The existing fade/filter ramp must retain its bounded first PCM onset")
    }

    private fun assertListenerSpeed(speed: Float) {
        val reference = assertNotNull(capture(record = true).samples)
        assertTrue(reference.all { abs(it) < 1f }, "The reference must not engage the output limiter")
        val stretcher = DesktopAudioSpeed(2, pcm.sampleRate).apply { this.speed = speed }
        var offset = 0
        var producedFrames = 0L
        var expected = -1L
        while (offset < reference.size && expected < 0) {
            val count = minOf(8_192, reference.size - offset)
            val rendered = stretcher.process(reference.copyOfRange(offset, offset + count), count)
            for (index in 0 until stretcher.outputCount step 2) {
                if (abs(rendered[index]) > .0000001f || abs(rendered[index + 1]) > .0000001f) {
                    expected = producedFrames + index / 2
                    break
                }
            }
            producedFrames += stretcher.outputCount / 2
            offset += count
        }
        assertTrue(expected >= 0)
        for (queue in listOf(0L, 12_000L)) {
            val actual = capture(queuedFrames = queue, speed = speed)
            assertTrue(abs(actual.firstNonzero - expected) <= 1,
                "speed=$speed queued=$queue: reference PCM onset=$expected, actual=${actual.firstNonzero}")
        }
    }

    private fun capture(queuedFrames: Long = 0, blockFrames: Int = 960, sourceRate: Int = 48_000,
        shortWrites: Boolean = false, startMs: Long = 0, speed: Float = 1f, record: Boolean = false,
        marker: Boolean = false, seconds: Double = 12.0, ramp: Boolean = false): Capture {
        val output = Output(queuedFrames, shortWrites, (seconds / speed * pcm.sampleRate).roundToLong(), record)
        val sources = ConcurrentHashMap<String, Source>()
        val handoffs = AtomicInteger()
        val a = Song("onset-A", "Generated silence", "Fixture", null, durationText = "0:10")
        val b = Song("onset-B", "Generated marker", "Fixture", null, durationText = "0:10")
        val engine = DesktopPlaybackEngine({}, { handoffs.incrementAndGet() }, output,
            { Result.success(DesktopLiveResolution(DesktopStream("fixture://onset", sourceId = "onset-fixture"))) },
            { song, _, start ->
                val incoming = song.videoId == b.videoId
                val rate = if (incoming) 48_000 else sourceRate
                val source = Source(incoming, rate, blockFrames, start * 1_000, marker, ramp).also { sources[song.videoId] = it }
                DesktopDecodeAhead(source, pcm.copy(sampleRate = rate), durationUs, null, aheadSamples = 96_000)
            }, false)
        output.engine = engine
        try {
            engine.setCrossfadeSeconds(4)
            engine.setPlaybackSpeed(speed)
            engine.load(a, false)
            await("outgoing open") { !engine.state.value.isLoading && sources.containsKey(a.videoId) }
            engine.prepareNext(b)
            val opened = DesktopPlaybackEngine::class.java.getDeclaredField("nextDeckOpenId").apply { isAccessible = true }
            await("incoming ready") { opened.get(engine) == b.videoId }
            if (startMs > 0) {
                val seeks = engine.state.value.seeks
                engine.seekTo(startMs)
                await("seek applied") { engine.state.value.seeks > seeks }
            }
            engine.play()
            await("fixed capture length") { output.submitted.get() == output.targetFrames }
            engine.pause()
            await("pause") { !engine.state.value.isPlaying }
            engine.shutdown()
            val plan = assertNotNull(output.firstPlan)
            assertEquals(1, handoffs.get())
            assertTrue(engine.state.value.error == null)
            val playhead = DesktopPlaybackEngine::class.java.getDeclaredField("playhead")
                .apply { isAccessible = true }.get(engine) as DesktopPlayhead
            val heardFrames = output.framesPlayed()
            return Capture(plan, output.firstBlendFrame, output.firstNonzero.get(), output.captured,
                heardFrames, playhead.at(heardFrames))
        } finally { engine.shutdown() }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate() && System.nanoTime() < limit) Thread.sleep(2)
        assertTrue(predicate(), message)
    }
}
