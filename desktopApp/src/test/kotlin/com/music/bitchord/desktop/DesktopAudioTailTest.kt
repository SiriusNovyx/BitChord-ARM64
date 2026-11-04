package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAudioTailTest {
    @Test fun `equal-rate FFmpeg decoding retains every PCM frame`() = decoderFrames(48_000, 48_000)
    @Test fun `downsampling drains the FFmpeg resampler at EOF`() = decoderFrames(48_000, 24_000)
    @Test fun `upsampling drains the FFmpeg resampler at EOF`() = decoderFrames(24_000, 48_000)

    private fun decoderFrames(from: Int, to: Int) = withWave(from) { path ->
        val decoder = DesktopAudioDecoder()
        try {
            decoder.open(path, requested = DesktopPcmFormat(to, 2, 4, true),
                preserveSourceSampleRate = false).getOrThrow()
            val result = decodeAll(decoder)
            assertEquals(to / 10 * 2, result.size, "A 100 ms PCM fixture must retain its complete consumer-clock duration")
            repeat(3) { assertNull(decoder.readSamples(), "EOF must not emit the buffered tail twice") }
            assertTrue(result.takeLast(32).all { it < -.1f }, "The final source marker must reach the consumer")
        } finally { decoder.close() }
    }

    @Test fun `seek after EOF starts with a fresh resampler history`() = withWave(48_000) { path ->
        val decoder = DesktopAudioDecoder()
        try {
            decoder.open(path, requested = DesktopPcmFormat(24_000, 2, 4, true),
                preserveSourceSampleRate = false).getOrThrow()
            val first = decodeAll(decoder)
            assertTrue(decoder.seek(0))
            val replayed = decodeAll(decoder)
            assertContentEquals(first.take(256), replayed.take(256), "Old negative tail samples must not precede the positive beginning after a seek")
            assertEquals(first.size, replayed.size, "Seeking must reset resampler delay and EOF state")
        } finally { decoder.close() }
    }

    @Test fun `seeking into a short terminal fragment retains its resampled frames`() = withWave(48_000) { path ->
        val decoder = DesktopAudioDecoder()
        try {
            decoder.open(path, requested = DesktopPcmFormat(24_000, 2, 4, true),
                preserveSourceSampleRate = false).getOrThrow()
            decoder.readSamples()
            assertTrue(decoder.seek(99_500))
            val tail = decodeAll(decoder)
            assertEquals(24, tail.size, "Half a millisecond must produce twelve stereo frames, including the filter tail")
            assertTrue(tail.all { it < -.1f }, "The seek must not replay the old positive segment")
        } finally { decoder.close() }
    }

    @Test fun `sub-filter PCM fragments retain their finite consumer-clock duration`() {
        for ((from, to) in listOf(48_000 to 24_000, 24_000 to 48_000, 44_100 to 48_000)) {
            for (frames in listOf(1, 2, 8, 24, 65)) withWave(from, frames) { path ->
                val decoder = DesktopAudioDecoder()
                try {
                    decoder.open(path, requested = DesktopPcmFormat(to, 2, 4, true),
                        preserveSourceSampleRate = false).getOrThrow()
                    val data = decodeAll(decoder)
                    val expected = ((frames.toLong() * to + from - 1) / from).toInt() * 2
                    assertEquals(expected, data.size, "$frames input frames at $from → $to")
                    assertTrue(data.all { it.isFinite() } && data.any { abs(it) > .00001f })
                    assertNull(decoder.readSamples())
                } finally { decoder.close() }
            }
        }
    }

    private fun withWave(rate: Int, frames: Int = rate / 10, test: (String) -> Unit) {
        val bytes = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
        bytes.put("data".toByteArray()).putInt(frames * 4)
        repeat(frames) { frame ->
            val sample = (if (frame >= frames - 128) -10_000 else 8_000).toShort()
            bytes.putShort(sample).putShort(sample)
        }
        val file = Files.createTempFile("bitchord-eof-tail-", ".wav")
        try { Files.write(file, bytes.array()); test(file.toString()) }
        finally { Files.deleteIfExists(file) }
    }

    private fun decodeAll(decoder: DesktopAudioDecoder): FloatArray {
        val blocks = mutableListOf<FloatArray>()
        while (true) {
            val data = decoder.readSamples() ?: break
            blocks += data.copyOf(decoder.sampleCount)
        }
        val result = FloatArray(blocks.sumOf { it.size })
        var offset = 0
        for (block in blocks) { block.copyInto(result, offset); offset += block.size }
        return result
    }

    @Test fun `engine rate conversion retains the final frames across short writes`() {
        val result = renderEngine(from = 44_100, frames = 4_410, shortWrites = true)
        assertEquals(4_800, result.frames)
        assertTrue(result.last.all { it < -.1f }, "The converter's retained endpoint must reach the sink")
    }

    @Test fun `a sub-window clip at listener speed still reaches the sink`() {
        val result = renderEngine(from = 48_000, frames = 1_000, speed = 1.25f)
        assertEquals(1_000, result.frames, "The existing stretcher drains its short tail unstretched rather than discarding it")
        assertEquals(20_833L, result.sourceUs, "An unstretched terminal buffer must advance the source clock at 1x")
        assertTrue(result.last.all { it < -.1f })
    }

    @Test fun `an inherited tempo buffer drains when its promoted source ends`() {
        val result = renderEngine(from = 48_000, frames = 1_000, tempo = true)
        assertEquals(1_000, result.frames)
        assertEquals(20_833L, result.sourceUs)
        assertTrue(result.last.all { it < -.1f })
    }

    @Test fun `a queued tempo block keeps the rate that rendered it`() {
        renderEngine(48_000, 1_000, inspectPaused = { engine, track ->
            val tempo = DesktopTempoBuffer(2, 48_000).apply { speed = 1.1f }
            tempo.push(FloatArray(10_000) { .2f }, 10_000)
            assertTrue(tempo.available >= 256)
            tempo.speed = 1f
            tempo.take(256)
            installTempo(track, tempo, requested = 1.0, drained = false)
            assertEquals(1.1, observedDeckRate(engine, track), .00001,
                "The next requested speed must not relabel already queued PCM")
        })
    }

    @Test fun `a read crossing stretched output and its raw tail accounts for both rates`() {
        renderEngine(48_000, 1_000, inspectPaused = { engine, track ->
            val tempo = DesktopTempoBuffer(2, 48_000).apply { speed = 1.1f }
            tempo.push(FloatArray(10_000) { .2f }, 10_000)
            val stretched = tempo.available
            tempo.finish()
            val raw = minOf(64, tempo.available - stretched)
            assertTrue(stretched > 0 && raw > 0)
            tempo.take(stretched + raw)
            installTempo(track, tempo, requested = 1.1, drained = true)
            val expected = (stretched * 1.1 + raw) / (stretched + raw)
            assertEquals(expected, observedDeckRate(engine, track), .00001,
                "A draining FIFO must not count its already stretched prefix at 1x")
        })
    }

    private fun installTempo(track: Any, tempo: DesktopTempoBuffer, requested: Double, drained: Boolean) {
        track.javaClass.getDeclaredField("tempo").apply { isAccessible = true }.set(track, tempo)
        track.javaClass.getDeclaredField("tempoRate").apply { isAccessible = true }.setDouble(track, requested)
        track.javaClass.getDeclaredField("tempoDrained").apply { isAccessible = true }.setBoolean(track, drained)
    }

    private fun observedDeckRate(engine: DesktopPlaybackEngine, track: Any): Double =
        DesktopPlaybackEngine::class.java.getDeclaredMethod("deckRate", track.javaClass)
            .apply { isAccessible = true }.invoke(engine, track) as Double

    private data class Captured(val frames: Int, val last: List<Float>, val sourceUs: Long = 0)

    private class FiniteSource(private val frames: Int) : DesktopSampleSource {
        private var next = 0
        val closed = AtomicBoolean()
        override var sampleCount = 0
        override fun readSamples(): FloatArray? {
            if (next >= frames || closed.get()) return null
            val count = minOf(127, frames - next)
            val data = FloatArray(count * 2) { index -> if (next + index / 2 >= frames - 128) -.3f else .2f }
            next += count
            sampleCount = data.size
            return data
        }
        override fun seek(micros: Long) = false
        override fun cancelPendingRead() = Unit
        override fun close() { closed.set(true) }
    }

    private class Output(private val shortWrites: Boolean) : DesktopAudioOutput {
        override val format = DesktopPcmFormat(48_000, 2, 4, true)
        override var gain = 1f
        override var isOpen = false
        override val deviceName = "Deterministic EOF capture"
        override val bufferBytes = 0
        val written = AtomicLong()
        private val samples = mutableListOf<Float>()
        override fun isUsingSelection(id: String) = isOpen
        override fun open(requested: DesktopPcmFormat) = Result.success(format).also { isOpen = true }
        @Synchronized override fun write(input: FloatArray, count: Int): Int {
            val accepted = if (shortWrites) minOf(count, 14) else count
            repeat(accepted) { index ->
                assertTrue(input[index].isFinite() && abs(input[index]) < 1f)
                samples += input[index] * gain
            }
            written.addAndGet((accepted / 2).toLong())
            return accepted * 4
        }
        @Synchronized fun result() = Captured(written.get().toInt(), samples.takeLast(32))
        override fun framesPlayed() = written.get()
        override fun pause() = Unit
        override fun resume() = Unit
        override fun flush() = Unit
        override fun close() { isOpen = false }
    }

    private fun renderEngine(from: Int, frames: Int, shortWrites: Boolean = false,
        speed: Float = 1f, tempo: Boolean = false,
        inspectPaused: ((DesktopPlaybackEngine, Any) -> Unit)? = null): Captured {
        val source = FiniteSource(frames)
        val output = Output(shortWrites)
        val ended = CountDownLatch(1)
        val endings = AtomicInteger()
        val song = Song("tail-fixture", "EOF fixture", "Generated PCM", null, durationText = "0:01")
        val engine = DesktopPlaybackEngine({ endings.incrementAndGet(); ended.countDown() }, {}, output,
            { Result.success(DesktopLiveResolution(DesktopStream("fixture://eof", sourceId = "tail-fixture"))) },
            { _, _, _ -> DesktopDecodeAhead(source, DesktopPcmFormat(from, 2, 4, true),
                frames.toLong() * 1_000_000 / from, null, aheadSamples = 2_048) }, false)
        try {
            engine.setPlaybackSpeed(speed)
            engine.load(song, playWhenReady = false)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (engine.state.value.isLoading && System.nanoTime() < deadline) Thread.sleep(2)
            assertTrue(engine.state.value.song?.videoId == song.videoId && !engine.state.value.isLoading)
            val current = DesktopPlaybackEngine::class.java.getDeclaredField("current").apply { isAccessible = true }.get(engine)
            if (inspectPaused != null) {
                inspectPaused(engine, current)
                return output.result()
            }
            if (tempo) {
                // A promoted deck retains this processor until its beatmatch ease completes.
                installTempo(current, DesktopTempoBuffer(2, 48_000).apply { this.speed = 1.1f }, 1.1, false)
            }
            engine.play()
            assertTrue(ended.await(3, TimeUnit.SECONDS), "Finite source did not finish: ${engine.state.value}")
            assertEquals(1, endings.get())
            assertNull(engine.state.value.error)
            val playhead = DesktopPlaybackEngine::class.java.getDeclaredField("playhead").apply { isAccessible = true }.get(engine) as DesktopPlayhead
            return output.result().copy(sourceUs = playhead.at(output.written.get()))
        } finally { engine.shutdown(); assertTrue(source.closed.get()) }
    }
}
