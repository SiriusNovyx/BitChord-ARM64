package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.BeatTracker
import com.music.bitchord.playback.smart.CurveWindow
import kotlin.test.*
import org.junit.Test

class DesktopAnalysisWindowsTest {
    @Test fun realResampledStereoUsesCountedClockAcrossAnOverlap() {
        val inputRate = 48_000
        val frames = inputRate * 4
        val bytes = java.nio.ByteBuffer.allocate(44 + frames * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(2).putInt(inputRate).putInt(inputRate * 4).putShort(4).putShort(16)
        bytes.put("data".toByteArray()).putInt(frames * 4)
        repeat(frames) { frame ->
            val value = (frame / inputRate + 1) * 4000
            bytes.putShort(value.toShort()).putShort((-value).toShort())
        }
        val file = java.nio.file.Files.createTempFile("bitchord-region-clock-", ".wav")
        val decoder = DesktopAudioDecoder()
        try {
            java.nio.file.Files.write(file, bytes.array())
            decoder.open(file.toString(), requested = DesktopPcmFormat(44_100, 2, 4, true), preserveSourceSampleRate = false).getOrThrow()
            assertEquals(44_100, decoder.outputFormat.sampleRate)
            val reader = DesktopAnalysisWindowReader(44_100, { false }) { decoder.readSamples()?.copyOf(decoder.sampleCount) }
            reader.read(0.0, 2.2)!!
            val overlap = reader.read(1.2, 3.4)!!
            assertEquals(1.2, overlap.start, 1e-10)
            assertEquals(97_020, overlap.left.size)
            assertEquals(8000.0 / 32768, overlap.left[4410].toDouble(), 1e-4)
            assertEquals(-12000.0 / 32768, overlap.right[44100].toDouble(), 1e-4)
            DesktopAnalysisRuntime.ensureStarted()
            assertTrue(DesktopAnalysisRuntime.available)
            val analyzer = DesktopTrackAnalyzer()
            try {
                val method = DesktopTrackAnalyzer::class.java.getDeclaredMethod("analyse", String::class.java,
                    DesktopStream::class.java, Double::class.javaPrimitiveType, DesktopPreparationQueue.Pass::class.java)
                method.isAccessible = true
                val analysis = assertNotNull(method.invoke(analyzer, "region-contract-fixture", DesktopStream(file.toString()),
                    4.0, DesktopPreparationQueue.Pass()) as com.music.bitchord.playback.smart.TrackAnalysis?)
                val level = analysis.levelWindows.single()
                assertEquals(0.0, level.startTime)
                assertEquals(10.0, level.frameRate)
                assertEquals(4.0, level.endTime, 1e-8)
                // These channels cancel in the mono level mix while remaining distinct for vocals.
                assertTrue(level.values.all { it == 0f })
                val vocal = analysis.vocalWindows.single()
                assertTrue(vocal.endTime <= analysis.duration)
                assertTrue(vocal.values.isNotEmpty())
                assertEquals(analysis.energyCurve.size, analysis.vocalActivityMask.size)
            } finally { analyzer.release() }
        } finally {
            decoder.close()
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    @Test fun windowsCoverShortTracksOnceAndLeaveLongMiddleUnmeasured() {
        val short = DesktopAnalysisWindows.plan(40.0, 22.2)
        assertEquals(0.0, short.first().first)
        assertEquals(40.0, short.last().second)
        assertEquals(2, short.size)
        val long = DesktopAnalysisWindows.plan(240.0, 22.2)
        assertEquals(5, long.size)
        assertEquals(43.4, long[1].second, 1e-8)
        assertEquals(175.4, long[2].first, 1e-8)
        assertTrue(long.all { it.second - it.first <= 22.2 + 1e-8 })
    }

    @Test fun countedFramesSurviveIrregularBlocksOverlapAndSkippedMiddle() {
        var frame = 0
        val reader = DesktopAnalysisWindowReader(10, { false }) {
            if (frame >= 100) null else FloatArray(14) { i -> (frame + i / 2).toFloat() }.also { frame += 7 }
        }
        val first = reader.read(0.0, 2.2)!!
        assertEquals(22, first.left.size)
        val overlap = reader.read(1.2, 3.4)!!
        assertEquals(1.2, overlap.start, 1e-8)
        assertEquals((12..33).map(Int::toFloat), overlap.left.toList())
        val tail = reader.read(8.0, 10.0)!!
        assertEquals((80..99).map(Int::toFloat), tail.right.toList())
    }

    @Test fun truncatedDecodeNeverPadsMeasuredCoverage() {
        var sent = false
        val reader = DesktopAnalysisWindowReader(10, { false }) {
            if (sent) null else FloatArray(26) { 1f }.also { sent = true }
        }
        val window = reader.read(0.0, 2.2)!!
        assertEquals(13, window.left.size)
        assertEquals(1.3, window.seconds, 1e-8)
        assertNull(reader.read(2.0, 4.2))
        val levels = DesktopAnalysisWindows.levels(window)!!
        assertEquals(1.3, levels.endTime, 1e-8)
    }

    @Test fun incompleteStructuralDecodeCannotTurnItsEndpointIntoTheTail() {
        assertNull(DesktopAnalysisWindows.measuredDuration(13_000, 10_000.0, 5.0))
        assertEquals(5.0, DesktopAnalysisWindows.measuredDuration(50_000, 10_000.0, 5.0))
        assertEquals(6.0, DesktopAnalysisWindows.measuredDuration(60_000, 10_000.0, 5.0))
        assertEquals(4.999, DesktopAnalysisWindows.measuredDuration(49_990, 10_000.0, 5.0))
        assertEquals(5.0, DesktopAnalysisWindows.measuredDuration(50_000, 10_000.0, 6.5, 5.0))
        assertNull(DesktopAnalysisWindows.measuredDuration(50_000, 10_000.0, 6.5, 6.5))
    }

    @Test fun cancellationDiscardsPartialWindow() {
        var cancelled = false
        val reader = DesktopAnalysisWindowReader(10, { cancelled }) {
            FloatArray(10).also { cancelled = true }
        }
        assertNull(reader.read(0.0, 2.2))
    }

    @Test fun sideTemposStayIndependentAndSeamsDoNotDuplicateBeats() {
        fun grid(beats: List<Double>) = BeatTracker.Grid(beats, beats.filterIndexed { i, _ -> i % 4 == 0 }, 120.0, .5, beats.first(), .9)
        val regions = listOf(
            DesktopAnalysisWindows.Evidence(0.0, 3.0, grid(listOf(0.0, .5, 1.0, 1.5, 2.0, 2.5))),
            DesktopAnalysisWindows.Evidence(2.0, 3.0, grid(listOf(2.0, 2.5, 3.0, 3.5, 4.0, 4.5))),
            DesktopAnalysisWindows.Evidence(20.0, 8.0, grid((20..27).map(Int::toDouble))),
        )
        val head = DesktopAnalysisWindows.side(regions, 0.0, 5.0)!!
        assertEquals(120.0, head.bpm, 1e-8)
        assertEquals(head.beats.distinct(), head.beats)
        assertEquals(60.0, DesktopAnalysisWindows.side(regions, 20.0, 28.0)!!.bpm, 1e-8)
    }

    @Test fun vocalOverlayKeepsFallbackInGapsAndNeverExtendsRawCoverage() {
        val raw = CurveWindow(2.0, 10.0, floatArrayOf(.1f, .9f))
        val mask = DesktopAnalysisWindows.overlay(listOf(1.9, 2.0, 2.1, 2.2), listOf(.8, .8, .8, .8), listOf(raw))
        assertEquals(.8, mask[0])
        assertEquals(.1, mask[1], 1e-6)
        assertEquals(.9, mask[2], 1e-6)
        assertEquals(.8, mask[3])
        assertEquals(2.2, raw.endTime, 1e-8)
    }
}
