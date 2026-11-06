package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopOutputRefreshTest {
    private class Source : DesktopSampleSource {
        private val first = AtomicBoolean(true)
        private val closed = CountDownLatch(1)
        override var sampleCount = 0
        override fun readSamples(): FloatArray? {
            if (first.getAndSet(false)) return FloatArray(960) { .1f }.also { sampleCount = it.size }
            closed.await()
            sampleCount = 0
            return null
        }
        override fun seek(micros: Long) = true
        override fun cancelPendingRead() { closed.countDown() }
        override fun close() { closed.countDown() }
    }

    private class Attempt(val fail: Boolean = false, val closesLine: Boolean = false) {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
    }

    private class Output : DesktopAudioOutput {
        @Volatile override var format = DesktopPcmFormat(48_000, 2, 2)
        @Volatile override var isOpen = false
        override var gain = 1f
        override val deviceName = "Output refresh fixture"
        override val apiName: String? get() = "Fixture shared".takeIf { isOpen }
        override val bufferBytes = 0
        val next = AtomicReference<Attempt?>()
        val accepted = CopyOnWriteArrayList<DesktopPcmFormat>()
        override fun isUsingSelection(id: String) = isOpen
        override fun open(requested: DesktopPcmFormat): Result<DesktopPcmFormat> {
            next.getAndSet(null)?.let { attempt ->
                attempt.entered.countDown()
                check(attempt.proceed.await(5, TimeUnit.SECONDS))
                if (attempt.fail) {
                    if (attempt.closesLine) isOpen = false
                    return Result.failure(IOException("Refused fixture output format"))
                }
            }
            format = requested
            isOpen = true
            accepted += requested
            return Result.success(requested)
        }
        override fun write(samples: FloatArray, count: Int) = 0
        override fun framesPlayed() = 0L
        override fun pause() = Unit
        override fun resume() = Unit
        override fun flush() = Unit
        override fun close() { isOpen = false }
    }

    @Test fun completedOutputChangeSignalsSameTrackWhilePlayingOrPaused() {
        for (playing in listOf(true, false)) withEngine(playing) { engine, output, _ ->
            val before = engine.state.value
            val revision = revision(engine)
            val held = Attempt()
            output.next.set(held)
            try {
                engine.setOutputPrecision("FLOAT_32")
                assertTrue(held.entered.await(3, TimeUnit.SECONDS))
                assertEquals(revision, revision(engine), "Do not publish the requested format before open completes")
                assertFalse(engine.pipeline().outputIsFloat == true)
                held.proceed.countDown()
                await("completed output observation") { output.format.isFloat && revision(engine) > revision }
                assertEquals(before.song, engine.state.value.song)
                assertEquals(before.streamFormat, engine.state.value.streamFormat)
                assertEquals(playing, engine.state.value.isPlaying)
                assertTrue(engine.pipeline().outputIsFloat == true)
                assertEquals("Fixture shared", engine.pipeline().outputApi)
            } finally { held.proceed.countDown() }
        }
    }

    @Test fun failedOutputChangeReportsTheLineThatActuallyRemains() {
        for (closesLine in listOf(false, true)) withEngine(true) { engine, output, _ ->
            val revision = revision(engine)
            val held = Attempt(fail = true, closesLine = closesLine)
            output.next.set(held)
            try {
                engine.setOutputPrecision("FLOAT_32")
                assertTrue(held.entered.await(3, TimeUnit.SECONDS))
                assertEquals(revision, revision(engine))
                held.proceed.countDown()
                await("output refusal") { engine.state.value.error != null }
                if (closesLine) {
                    await("closed output observation") { revision(engine) > revision }
                    assertNull(engine.pipeline().outputSampleRateHz)
                    assertNull(engine.pipeline().outputIsFloat)
                    assertNull(engine.pipeline().outputApi)
                } else {
                    assertEquals(48_000, engine.pipeline().outputSampleRateHz)
                    assertEquals(false, engine.pipeline().outputIsFloat)
                }
            } finally { held.proceed.countDown() }
        }
    }

    @Test fun rapidPrecisionChangesPublishCompletedFormatsWithoutChangingTheTrack() {
        withEngine(true) { engine, output, _ ->
            val before = engine.state.value
            val revision = revision(engine)
            val held = Attempt()
            output.next.set(held)
            try {
                engine.setOutputPrecision("FLOAT_32")
                assertTrue(held.entered.await(3, TimeUnit.SECONDS))
                engine.setOutputPrecision("PCM_16")
                assertEquals(revision, revision(engine))
                held.proceed.countDown()
                await("latest completed precision") { output.accepted.size >= 3 && revision(engine) >= revision + 2 }
                assertEquals(listOf(false, true, false), output.accepted.takeLast(3).map { it.isFloat })
                assertEquals(false, engine.pipeline().outputIsFloat)
                assertEquals(before.song, engine.state.value.song)
                assertEquals(before.streamFormat, engine.state.value.streamFormat)
                assertTrue(engine.state.value.isPlaying)
            } finally { held.proceed.countDown() }
        }
    }

    @Test fun reloadingTheSameTrackPreservesTheObservedOutputRevision() {
        withEngine(false) { engine, output, opened ->
            engine.setOutputPrecision("FLOAT_32")
            await("initial format revision") { output.format.isFloat && revision(engine) > 0 }
            val revision = revision(engine)
            val song = engine.state.value.song!!
            engine.load(song, playWhenReady = false)
            await("same track reloaded") { opened.get() >= 2 && !engine.state.value.isLoading }
            assertEquals(revision, revision(engine), "A state reset must not lose a completed output observation")
            assertEquals(true, engine.pipeline().outputIsFloat)
        }
    }

    private fun revision(engine: DesktopPlaybackEngine): Long = engine.state.value.javaClass.methods
        .firstOrNull { it.name == "getOutputRevision" }?.invoke(engine.state.value) as? Long ?: 0L

    private inline fun withEngine(playing: Boolean, block: (DesktopPlaybackEngine, Output, AtomicInteger) -> Unit) {
        val output = Output()
        val opened = AtomicInteger()
        val engine = DesktopPlaybackEngine({}, {}, output,
            { Result.success(DesktopLiveResolution(DesktopStream("https://fixture.invalid"), null)) },
            { _, _, _ ->
                opened.incrementAndGet()
                DesktopDecodeAhead(Source(), DesktopPcmFormat(48_000, 2, 4, isFloat = true),
                    60_000_000, null, aheadSamples = 96_000)
            }, false)
        try {
            engine.load(Song("output-refresh-fixture", "Fixture", "Artist", null, durationText = "1:00"),
                playWhenReady = playing)
            await("initial output") { !engine.state.value.isLoading && output.isOpen }
            block(engine, output, opened)
        } finally {
            output.next.getAndSet(null)?.proceed?.countDown()
            engine.release()
        }
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!predicate() && System.nanoTime() < until) Thread.sleep(5)
        assertTrue(predicate(), "Timed out waiting for $label")
    }
}
