package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.smart.CurveWindow
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*

class DesktopLoopMixEngineTest {
    private val rate = 8_000
    private val duration = 96.0

    private class Output(private val rate: Int) : DesktopAudioOutput {
        override var format = DesktopPcmFormat(rate, 2, 4, true)
        override var gain = 1f
        override var isOpen = false
        override val deviceName = "Loop engine generated PCM"
        override val bufferBytes = 0
        val written = AtomicLong()
        val heard = AtomicLong()
        val budget = AtomicLong(256)
        override fun isUsingSelection(id: String) = isOpen
        override fun open(requested: DesktopPcmFormat) = Result.success(format).also { isOpen = true }
        override fun write(samples: FloatArray, count: Int): Int {
            val frames = minOf(count / 2L, (budget.get() - written.get()).coerceAtLeast(0)).toInt()
            assertTrue((0 until frames * 2).all { samples[it].isFinite() })
            written.addAndGet(frames.toLong())
            return frames * format.frameBytes
        }
        override fun framesPlayed() = heard.get()
        override fun pause() = Unit
        override fun resume() = Unit
        override fun flush() { heard.set(written.get()) }
        override fun close() { isOpen = false }
    }

    @Test fun measuredExactRecordingPreparesNativeLoopAndUsesDeviceClock() = exercise()
    @Test fun nativeLoopHandsOffAtMappedIncomingPositionAndContinuesRegularDecoder() = exercise("handover")
    @Test fun seekingBeyondPreparedLoopRetiresItsNativeGraph() = exercise("seek")
    @Test fun manualSkipRetainsTheSimpleFadeAndRetiresPreparedLoop() = exercise("skip")
    @Test fun cachedAnalysisWithoutRecordingProvenanceKeepsFallback() = exercise("cached")
    @Test fun changedRecordingCannotReuseMeasuredLoopEvidence() = exercise("mismatch")
    @Test fun replacedLocalFileCannotReuseMeasuredLoopEvidence() = exercise("replaced")
    @Test fun listenerSpeedKeepsTheTempoAwareFallback() = exercise("speed")
    @Test fun silenceSkippingKeepsTheSourceTimeFallback() = exercise("silence")
    @Test fun measuredLoopViewUsesTheAudibleClockAndClearsWhenObsolete() = exercise("view")
    @Test fun ordinaryMixViewWaitsForHeardAudioAndClearsOnSeek() = exercise("ordinary-view")
    @Test fun ordinaryMixViewSurvivesRenderedHandoverUntilQueuedAudioIsHeard() = exercise("ordinary-drain")

    private fun exercise(mode: String = "clock") {
        val path = Files.createTempFile("bitchord-loop-engine-", ".wav")
        val frames = (duration * rate).toInt()
        val bytes = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
        bytes.put("data".toByteArray()).putInt(frames * 4)
        repeat(frames) { i -> val v = (sin(i * 2 * PI * 220 / rate) * 2_000).toInt().toShort(); bytes.putShort(v).putShort(v) }
        Files.write(path, bytes.array())
        val suffix = UUID.randomUUID().toString()
        val a = Song("loop-A-$suffix", "Generated outgoing", "Fixture", null, durationText = "1:36")
        val b = a.copy(videoId = "loop-B-$suffix", title = "Generated incoming")
        val stream = DesktopStream(path.toString())
        fun analysis(id: String) = TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = id,
            duration = duration, bpm = 120.0, beatInterval = .5, beatConfidence = .99,
            contentEndTime = duration, vocalWindows = listOf(CurveWindow(0.0, 2.0, FloatArray(193))),
            levelWindows = listOf(CurveWindow(0.0, 2.0, FloatArray(193) { .7f })))
        val plan = LoopMix.plan(analysis(a.videoId), duration, analysis(b.videoId), duration, 1.0)
        assertNotNull(plan, "Generated measured instrumental fixture must be loop eligible")
        val output = Output(rate)
        val engine = DesktopPlaybackEngine({}, {}, output,
            { Result.success(DesktopLiveResolution(stream)) }, null, false)
        try {
            engine.load(a, false)
            await("current open") { !engine.state.value.isLoading && engine.state.value.song?.videoId == a.videoId }
            val analyzer = field(engine, "analyzer")!!
            @Suppress("UNCHECKED_CAST")
            val results = field(analyzer, "results") as ConcurrentHashMap<String, TrackAnalysis>
            results[a.videoId] = analysis(a.videoId); results[b.videoId] = analysis(b.videoId)
            @Suppress("UNCHECKED_CAST")
            (analyzer.javaClass.declaredFields.find { it.name == "measuredSources" }?.apply { isAccessible = true }
                ?.get(analyzer) as? ConcurrentHashMap<String, DesktopLoopMixRecording>)?.let {
                    if (mode != "cached") {
                        val measured = DesktopLoopMixRecording.capture(stream)!!
                        it[a.videoId] = if (mode == "mismatch") DesktopLoopMixRecording.capture(
                            stream.copy(url = "https://different.invalid/cut"))!! else measured
                        it[b.videoId] = measured
                    }
                }
            if (mode == "replaced") {
                val replacement = bytes.array().copyOf()
                replacement[replacement.lastIndex] = (replacement.last().toInt() xor 1).toByte()
                Files.write(path, replacement)
                Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000))
            }
            if (mode == "speed") engine.setPlaybackSpeed(1.25f)
            if (mode == "silence") engine.setSkipSilence(true)
            engine.setAutomixEnabled(true)
            engine.prepareNext(b)
            await("incoming open") { field(engine, "nextDeckOpenId") == b.videoId }
            engine.play()
            if (mode in listOf("cached", "mismatch", "replaced", "speed", "silence")) {
                await("loop decision") { field(engine, "preparedLoop") != null || field(engine, "loopFallbackReason") != null }
                assertNull(field(engine, "preparedLoop"), "Changed recordings must never arm a loop from older measurements")
                await("fallback reason") { field(engine, "loopFallbackReason") != null }
                val reason = field(engine, "loopFallbackReason") as String
                assertTrue(reason.contains(when(mode) { "speed" -> "speed"; "silence" -> "silence"; else -> "recording" }))
                assertNull(field(engine, "preparedLoop"))
                assertNull(field(engine, "activeLoop"))
                assertTrue(field(engine, "automixEnabled") as Boolean)
                await("Ordinary AutoMix planner must remain active") {
                    field(field(engine, "current")!!, "renderTransition") != null
                }
                return
            }
            await("native graph prepared from measured exact recording") { field(engine, "preparedLoop") != null }
            val prepared = field(engine, "preparedLoop")!!
            val graph = field(prepared, "graph") as DesktopLoopMixGraph
            assertTrue(graph.takeoverUs > 60_000_000)
            if (mode == "seek") {
                engine.seekTo(95_000)
                await("seek consumes obsolete prepared graph") { engine.state.value.positionMs == 95_000L && field(engine, "preparedLoop") == null }
                await("graph retirement runs on its owner") { runCatching { graph.heard(LoopMix.Side.IN) }.isFailure }
                assertNull(field(engine, "activeLoop"))
                return
            }
            if (mode == "skip" || mode == "ordinary-view" || mode == "ordinary-drain") {
                assertTrue(engine.beginSkipMix(b.videoId, 4))
                await("manual simple fade") { field(engine, "activePlan") != null }
                assertNull(field(engine, "preparedLoop"))
                assertNull(field(engine, "activeLoop"))
                if (mode == "ordinary-view" || mode == "ordinary-drain") {
                    await("ordinary view publication") { DesktopPlayerSettings.smartMixView.value != null }
                    val view = assertNotNull(DesktopPlayerSettings.smartMixView.value,
                        "An ordinary fade must publish the real planned deck view")
                    assertEquals(a.title, view.outgoing.title)
                    assertEquals(b.title, view.incoming.title)
                    assertEquals(com.music.bitchord.data.settings.MixViewTimeline.SOURCE, view.timeline)
                    assertTrue(view.fadeOut.isNotEmpty() && view.fadeIn.isNotEmpty())
                    assertTrue((view.fadeOut + view.fadeIn).all { it.isFinite() })
                    assertEquals(0f, DesktopPlayerSettings.smartMixProgress.value,
                        "Rendered lead must not advance the heard mix view")
                    output.budget.set(rate * 2L)
                    await("ordinary fade renders ahead") { output.written.get() == rate * 2L }
                    assertEquals(0f, DesktopPlayerSettings.smartMixProgress.value)
                    output.heard.set(rate.toLong())
                    await("ordinary view follows heard frames") { DesktopPlayerSettings.smartMixProgress.value > 0f }
                    await("ordinary local waveforms are decoded") {
                        DesktopPlayerSettings.smartMixView.value?.let {
                            it.outgoing.low.isNotEmpty() && it.incoming.low.isNotEmpty()
                        } == true
                    }
                    if (mode == "ordinary-drain") {
                        output.budget.set(rate * 7L)
                        await("rendered ordinary handover") {
                            output.written.get() == rate * 7L && !(field(engine, "blendActive") as Boolean)
                        }
                        assertNotNull(DesktopPlayerSettings.smartMixView.value,
                            "Queued unheard mix audio must retain its live view after decoder handover")
                        assertTrue(DesktopPlayerSettings.smartMixProgress.value < 1f)
                        output.heard.set(rate * 7L)
                        await("heard ordinary tail retires its live view") { DesktopPlayerSettings.smartMixView.value == null }
                        return
                    }
                    engine.pause()
                    await("ordinary view paused") { !engine.state.value.isPlaying }
                    val progress = DesktopPlayerSettings.smartMixProgress.value
                    Thread.sleep(60)
                    assertEquals(progress, DesktopPlayerSettings.smartMixProgress.value)
                    engine.seekTo(2_000)
                    await("ordinary seek clears obsolete view") { DesktopPlayerSettings.smartMixView.value == null }
                    assertEquals(0f, DesktopPlayerSettings.smartMixProgress.value)
                    Thread.sleep(60)
                    assertNull(DesktopPlayerSettings.smartMixView.value,
                        "A late waveform must not republish the obsolete transition")
                }
                return
            }
            output.budget.set((graph.takeoverUs * rate / 1_000_000) + rate)
            await("native graph starts") { field(engine, "activeLoop") != null }
            assertFalse(engine.state.value.mixing, "Unheard prepared native PCM must not move the heard clock")
            output.heard.set(graph.takeoverUs * rate / 1_000_000 + rate / 2)
            await("heard loop position is published") { engine.state.value.mixing }
            val active = field(engine, "activeLoop")!!
            val origin = field(active, "originFrame") as Long
            val expected = LoopMix.positionAt(graph.clock, LoopMix.Side.OUT,
                (output.heard.get() - origin).toDouble() / rate)!!
            assertTrue(kotlin.math.abs(engine.state.value.positionMs - expected * 1_000) <= 2)
            if (mode == "view") {
                val view = assertNotNull(DesktopPlayerSettings.smartMixView.value,
                    "An audible measured native loop must publish its actual transition view")
                assertEquals(a.title, view.outgoing.title)
                assertEquals(b.title, view.incoming.title)
                assertTrue(view.outgoing.low.isNotEmpty(), "Waveform must come from prepared PCM")
                assertTrue(view.incoming.low.isNotEmpty(), "Waveform must come from prepared PCM")
                val progress = ((output.heard.get() - origin).toDouble() / rate / graph.clock.elementAt).toFloat()
                assertEquals(progress, DesktopPlayerSettings.smartMixProgress.value, 1e-4f)
                engine.pause()
                await("view paused") { !engine.state.value.isPlaying }
                await("paused native beat publication") { DesktopPlayerSettings.smartMixBlend.value?.playing == false }
                val pausedBeat = DesktopPlayerSettings.smartMixBlend.value
                engine.javaClass.getDeclaredMethod("publishLoop", active.javaClass, java.lang.Boolean.TYPE)
                    .apply { isAccessible = true }.invoke(engine, active, false)
                assertSame(pausedBeat, DesktopPlayerSettings.smartMixBlend.value,
                    "An unchanged paused mix must not republish its beat pulse")
                val held = DesktopPlayerSettings.smartMixProgress.value
                Thread.sleep(60)
                assertEquals(held, DesktopPlayerSettings.smartMixProgress.value)
                engine.seekTo(95_000)
                await("obsolete loop view clears") { DesktopPlayerSettings.smartMixView.value == null }
                assertEquals(0f, DesktopPlayerSettings.smartMixProgress.value)
                return
            }
            if (mode == "handover") {
                val handoff = origin + (graph.clock.handoffAt * rate).toLong() + 256
                output.budget.set(handoff)
                await("mix reaches the heard handoff") { output.written.get() == handoff }
                output.heard.set(handoff)
                await("incoming is displayed on the heard shared clock") { engine.state.value.song?.videoId == b.videoId }
                val atHandoff = LoopMix.positionAt(graph.clock, LoopMix.Side.IN, (handoff - origin).toDouble() / rate)!!
                assertTrue(kotlin.math.abs(engine.state.value.positionMs - atHandoff * 1_000) <= 2)
                val end = origin + graph.handoverFrame + (LoopMix.HANDOVER_SEC * rate).toLong()
                output.budget.set(end + rate)
                await("native handover and regular incoming PCM") { output.written.get() == end + rate }
                output.heard.set(end + rate)
                await("native graph retires after its device queue drains") { field(engine, "activeLoop") == null }
                await("regular incoming position is published") {
                    kotlin.math.abs(engine.state.value.positionMs - (graph.clock.elementTrackTime + LoopMix.HANDOVER_SEC + 1) * 1_000) <= 2
                }
                assertEquals(b.videoId, engine.state.value.song?.videoId)
                return
            }
            engine.pause()
            await("paused") { !engine.state.value.isPlaying }
            val position = engine.state.value.positionMs
            Thread.sleep(100)
            assertEquals(position, engine.state.value.positionMs)
        } finally { engine.shutdown(); Files.deleteIfExists(path) }
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.declaredFields
        .find { it.name == name }?.apply { isAccessible = true }?.get(owner)

    private fun await(message: String, predicate: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!predicate() && System.nanoTime() < end) Thread.sleep(2)
        assertTrue(predicate(), message)
    }
}
