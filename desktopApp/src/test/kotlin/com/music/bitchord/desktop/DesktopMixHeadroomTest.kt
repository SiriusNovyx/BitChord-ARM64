package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Captures the production mix immediately before its output; no device or listening claim. */
class DesktopMixHeadroomTest {
    private data class Signal(val hz: Double = 1000.0, val left: Double = .9,
                              val right: Double = left, val phase: Double = 0.0, val pulses: Boolean = false,
                              val handoffPeak: Boolean = false) {
        fun value(frame: Long, channel: Int): Float {
            if (handoffPeak) return if (frame in 719_990L until 720_000L) 1.8f else .5f
            val wave = if (pulses) { if (frame % 2400 < 24) 1.0 else 0.0 }
                       else sin(2 * PI * hz * frame / RATE + phase)
            return ((if (channel == 0) left else right) * wave).toFloat()
        }
    }

    private class Source(private val signal: Signal) : DesktopSampleSource {
        private val blocks = LinkedBlockingQueue<FloatArray>()
        val seeks = AtomicInteger()
        private var frame = 0L
        override var sampleCount = 0
        override fun readSamples(): FloatArray = blocks.take().also { sampleCount = it.size }
        override fun seek(micros: Long): Boolean { blocks.clear(); frame = 0; seeks.incrementAndGet(); return true }
        override fun cancelPendingRead() { blocks.offer(FloatArray(0)) }
        override fun close() = Unit
        fun emit(frames: Int) {
            val data = FloatArray(frames * 2)
            repeat(frames) { index ->
                data[index * 2] = signal.value(frame, 0)
                data[index * 2 + 1] = signal.value(frame, 1)
                frame++
            }
            blocks.put(data)
        }
    }

    private data class Capture(val frames: Long, val peak: Double, val overrange: Long,
                               val rms: Double, val midpointRms: Double, val digest: String,
                               val firstAfterHandoff: Float?)

    private class Output(private val shortWrites: Boolean) : DesktopAudioOutput {
        override var format = PCM
        override var gain = 1f
        override var isOpen = false
        override val deviceName = "Synthetic PCM capture"
        override val bufferBytes = 0
        val submitted = AtomicLong()
        val capture = AtomicBoolean()
        val pauseCalls = AtomicInteger()
        @Volatile var capturedFrames = 0L
        private var peak = 0.0
        private var overrange = 0L
        private var squares = 0.0
        private var midpointSquares = 0.0
        private var midpointFrames = 0L
        private val hash = MessageDigest.getInstance("SHA-256")
        private var firstAfterHandoff: Float? = null
        val markNext = AtomicBoolean()
        val marked = AtomicReference<Float?>()
        @Volatile var onAccepted: (() -> Unit)? = null
        override fun isUsingSelection(id: String) = isOpen
        override fun open(requested: DesktopPcmFormat) = Result.success(requested).also { format = requested; isOpen = true }
        @Synchronized override fun write(samples: FloatArray, count: Int): Int {
            val accepted = (if (shortWrites) max(1, count / format.channels / 2) * format.channels else count)
            if (markNext.compareAndSet(true, false)) marked.set(samples[0] * gain)
            if (capture.get()) {
                for (offset in 0 until accepted step 2) {
                    val frame = capturedFrames++
                    if (frame == FADE_FRAMES) firstAfterHandoff = samples[offset] * gain
                    if (frame >= FADE_FRAMES) continue
                    var framePeak = 0.0
                    for (channel in 0..1) {
                        val value = samples[offset + channel] * gain
                        assertTrue(value.isFinite(), "non-finite output PCM")
                        framePeak = max(framePeak, abs(value.toDouble()))
                        squares += value.toDouble() * value
                        if (frame in 355_200 until 364_800) midpointSquares += value.toDouble() * value
                        val bits = value.toRawBits()
                        repeat(4) { byte -> hash.update((bits ushr (byte * 8)).toByte()) }
                    }
                    if (frame in 355_200 until 364_800) midpointFrames++
                    peak = max(peak, framePeak)
                    if (framePeak > 1.00000012) overrange++
                }
            }
            submitted.addAndGet((accepted / format.channels).toLong())
            onAccepted?.invoke()
            return accepted * format.bytesPerSample
        }
        @Synchronized fun result() = Capture(minOf(capturedFrames, FADE_FRAMES), peak, overrange,
            sqrt(squares / (FADE_FRAMES * 2)), sqrt(midpointSquares / (midpointFrames * 2)),
            hash.digest().joinToString("") { "%02x".format(it) }, firstAfterHandoff)
        override fun framesPlayed() = submitted.get()
        override fun pause() { pauseCalls.incrementAndGet() }
        override fun resume() = Unit
        override fun flush() = Unit
        override fun close() { isOpen = false }
    }

    @Test fun `correlated loud decks do not reach the sink above full scale`() =
        safe("correlated", Signal(), Signal())
    @Test fun `quadrature decks retain safe peaks`() =
        safe("quadrature", Signal(), Signal(phase = PI / 2))
    @Test fun `unrelated loud frequencies do not reach the sink above full scale`() =
        safe("unrelated", Signal(hz = 997.0), Signal(hz = 641.0))
    @Test fun `opposite phase cancellation remains finite`() =
        safe("opposite", Signal(), Signal(phase = PI))
    @Test fun `one loud stereo side cannot overload the mix`() =
        safe("asymmetric", Signal(left = .95, right = .2), Signal(left = .95, right = -.2))
    @Test fun `sparse coincident transients do not overload the mix`() =
        safe("transients", Signal(left = .99, pulses = true), Signal(left = .99, pulses = true))
    @Test fun `low level overlap remains finite and safe`() =
        safe("low", Signal(left = .5), Signal(left = .5))
    @Test fun `different deck levels remain finite and safe`() =
        safe("unequal", Signal(), Signal(left = .25))

    @Test fun `half volume does not limit a mix that fits the final output headroom`() {
        val captured = runMix("half-volume", Signal(), Signal(), volume = .5f)
        assertTrue(captured.peak in .5351.. .5352, "software volume already provides headroom: $captured")
        assertEquals(0, captured.overrange)
    }

    @Test fun `mute preserves transition timing without leaking PCM`() {
        val captured = runMix("muted", Signal(), Signal(), volume = 0f)
        assertEquals(0.0, captured.peak)
        assertEquals(0.0, captured.rms)
    }

    @Test fun `pause during a mix anchors the displayed incoming position`() {
        runMix("pause-during-mix", Signal(left = .5), Signal(left = .5), pauseAtFrames = 439_680)
    }

    @Test fun `pause after promotion anchors the new track position`() {
        runMix("pause-after-promotion", Signal(left = .5), Signal(left = .5), pauseAtFrames = 727_680)
    }

    @Test fun `paused device position does not keep publishing fresh timestamps`() {
        runMix("paused-idle", Signal(left = .5), Signal(left = .5), afterCapture = { engine, output, _ ->
            val stopped = engine.state.value
            val cycles = output.pauseCalls.get()
            await("paused pump remains idle") { output.pauseCalls.get() >= cycles + 5 }
            assertEquals(stopped.positionMs, engine.state.value.positionMs)
            assertEquals(stopped.positionSampledAtNanos, engine.state.value.positionSampledAtNanos)
        })
    }

    @Test fun `release survives promotion and a subsequent seek clears it`() {
        val captured = runMix("promotion-release", Signal(left = 0.0), Signal(handoffPeak = true),
            afterCapture = { engine, output, sources ->
                val before = engine.state.value.seeks
                val seeks = sources.last().seeks.get()
                engine.seekTo(0)
                await("seek after promotion") { engine.state.value.seeks > before && sources.last().seeks.get() > seeks }
                output.markNext.set(true)
                engine.play()
                sources.last().emit(480)
                await("new position written") { output.marked.get() != null }
                assertEquals(.5f, output.marked.get(), "a seek must discard reduction from the previous position")
            })
        val firstAfter = assertNotNull(captured.firstAfterHandoff)
        assertTrue(firstAfter in .27f.. .29f,
            "promotion must retain release rather than jumping immediately to unity: $captured")
    }

    @Test fun `partial output writes cannot reprocess the same mixed samples`() {
        val whole = runMix("whole-writes", Signal(), Signal())
        val partial = runMix("partial-writes", Signal(), Signal(), shortWrites = true)
        assertEquals(whole.digest, partial.digest, "accepted PCM must be independent of output write sizes")
        assertEquals(whole.overrange, partial.overrange)
        assertTrue(partial.peak <= 1.00000012)
    }

    @Test fun `partial output writes cannot apply software volume again`() {
        val whole = runMix("whole-half-volume", Signal(), Signal(), volume = .5f)
        val partial = runMix("partial-half-volume", Signal(), Signal(), shortWrites = true, volume = .5f)
        assertEquals(whole.digest, partial.digest)
        assertTrue(partial.peak in .5351.. .5352)
    }

    private fun safe(name: String, first: Signal, second: Signal) {
        val captured = runMix(name, first, second)
        assertTrue(captured.peak <= 1.00000012, "$name PCM reaches the sink above full scale: $captured")
        assertEquals(0, captured.overrange)
    }

    private fun runMix(name: String, first: Signal, second: Signal, shortWrites: Boolean = false, volume: Float = 1f,
                       pauseAtFrames: Long? = null,
                       afterCapture: ((DesktopPlaybackEngine, Output, List<Source>) -> Unit)? = null): Capture {
        val output = Output(shortWrites)
        val sources = CopyOnWriteArrayList<Source>()
        val incomingReady = CompletableDeferred<Unit>()
        val handoffs = AtomicInteger()
        val engine = DesktopPlaybackEngine({}, { handoffs.incrementAndGet() }, output,
            { selected ->
                val pending = if (selected.videoId == "B") {
                    val delegate = CompletableDeferred<DesktopStream?>()
                    object : Deferred<DesktopStream?> by delegate {
                        override suspend fun await(): DesktopStream? {
                            incomingReady.complete(Unit)
                            return delegate.await()
                        }
                    }
                } else null
                Result.success(DesktopLiveResolution(DesktopStream("https://synthetic.invalid"), pending))
            },
            { selected, _, _ ->
                val source = Source(if (selected.videoId == "B") second else first).also { sources += it }
                DesktopDecodeAhead(source, PCM, 60_000_000, null, aheadSamples = 96_000)
            }, false)
        try {
            fun song(id: String) = Song(id, id, "Synthetic", null, durationText = "1:00")
            engine.setSkipMixEnabled(true)
            engine.setOutputPrecision("FLOAT32")
            engine.setVolume(volume)
            engine.load(song("A"), false)
            await("A ready") { sources.size == 1 && !engine.state.value.isLoading }
            engine.prepareNext(song("B"))
            runBlocking { withTimeout(10_000) { incomingReady.await() } }
            val initial = engine.state.value.seeks
            engine.seekTo(0)
            await("incoming installed") { engine.state.value.seeks > initial && sources.first().seeks.get() > 0 }
            engine.play()
            sources.first().emit(480)
            await("primer played") { output.submitted.get() >= 480 && !engine.state.value.awaitingAudio }
            assertTrue(engine.beginSkipMix("B", 15))
            val before = engine.state.value.seeks
            val sourceSeeks = sources.first().seeks.get()
            engine.seekTo(0)
            await("mix command barrier") { engine.state.value.seeks > before && sources.first().seeks.get() > sourceSeeks }
            output.capture.set(true)
            val injectedPause = AtomicBoolean()
            if (pauseAtFrames != null) output.onAccepted = {
                if (output.capturedFrames >= pauseAtFrames && injectedPause.compareAndSet(false, true)) engine.pause()
            }
            repeat(800) { sources.first().emit(960); sources.last().emit(960) }
            if (pauseAtFrames != null) {
                await("injected pause") { injectedPause.get() }
                val expected = pauseAtFrames * 1000 / RATE
                await("paused incoming position $expected must be published") {
                    !engine.state.value.isPlaying && abs(engine.state.value.positionMs - expected) <= 1
                }
                engine.play()
            }
            await("fade and handoff") {
                handoffs.get() == 1 && engine.state.value.song?.videoId == "B" && output.capturedFrames > FADE_FRAMES
            }
            val pausedAt = output.pauseCalls.get()
            engine.pause()
            await("audio pump processed pause") {
                !engine.state.value.isPlaying && output.pauseCalls.get() >= pausedAt + 2
            }
            output.capture.set(false)
            val captured = output.result()
            assertEquals(FADE_FRAMES, captured.frames)
            assertEquals(1, handoffs.get())
            val expectedPosition = output.capturedFrames * 1000 / RATE
            await("published incoming position must follow accepted source frames: expected=$expectedPosition") {
                abs(engine.state.value.positionMs - expectedPosition) <= 1
            }
            println("HEADROOM $name frames=${captured.frames} peak=${captured.peak} over=${captured.overrange}" +
                " rms=${captured.rms} midpointRms=${captured.midpointRms} sha256=${captured.digest} handoffs=${handoffs.get()}")
            afterCapture?.invoke(engine, output, sources)
            return captured
        } finally { engine.shutdown() }
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate() && System.nanoTime() < until) Thread.sleep(5)
        assertTrue(predicate(), label)
    }

    private companion object {
        const val RATE = 48_000
        const val FADE_FRAMES = 720_000L
        val PCM = DesktopPcmFormat(RATE, 2, 4, isFloat = true)
    }
}
