package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.AnalysisStore
import com.music.bitchord.playback.smart.TrackAnalysis
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAnalysisDecodeRateTest {
    @Test fun `feature analysis receives samples at its own clock rate`() = checkClock(48_000, 11_025)
    @Test fun `beat analysis receives samples at its own clock rate`() = checkClock(44_100, 22_050)

    @Test fun `source-rate legacy analysis is retained on disk but never reused as corrected evidence`() {
        val id = "legacy-clock-" + java.util.UUID.randomUUID()
        val home = DesktopAnalysisRuntime.analysisHome()
        val file = File(File(home, "smart_analysis"), "${id.hashCode().toUInt()}_${id.length}.json")
        val analyzer = DesktopTrackAnalyzer()
        try {
            AnalysisStore { home }.save(id, TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = id, bpm = 120.0))
            assertTrue(file.exists())
            assertFalse(analyzer.analysisFor(id).isUsable, "A schema-valid cache may still have the old incorrect PCM clock")
            assertTrue(file.exists(), "Migration must preserve the previous cache rather than deleting user files")
        } finally { analyzer.release(); file.delete() }
    }

    private fun checkClock(inputRate: Int, analysisRate: Int) {
        val duration = 2
        val frames = inputRate * duration
        val bytes = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(2).putInt(inputRate).putInt(inputRate * 4).putShort(4).putShort(16)
        bytes.put("data".toByteArray()).putInt(frames * 4)
        repeat(frames) { frame ->
            val sample = (sin(2 * PI * 220 * frame / inputRate) * 16_000).toInt().toShort()
            bytes.putShort(sample).putShort(sample)
        }
        val input = Files.createTempFile("bitchord-analysis-clock-", ".wav")
        Files.write(input, bytes.array())
        val analyzer = DesktopTrackAnalyzer()
        val pass = DesktopPreparationQueue.Pass()
        try {
            // Exercise the actual analysis decode path, without model inference or network access.
            val decode = DesktopTrackAnalyzer::class.java.getDeclaredMethod("decode",
                DesktopStream::class.java, Double::class.javaPrimitiveType, DesktopPreparationQueue.Pass::class.java)
            decode.isAccessible = true
            val actual = assertNotNull(decode.invoke(analyzer, DesktopStream(input.toString()), analysisRate.toDouble(), pass) as FloatArray?)
            assertTrue(abs(actual.size - duration * analysisRate) <= 64,
                "$duration seconds at $analysisRate Hz must not be interpreted as ${actual.size.toDouble() / analysisRate} seconds (${actual.size} samples)")
            var crossings = 0
            for (index in 1 until actual.size) if (actual[index - 1] <= 0 && actual[index] > 0) crossings++
            val hz = crossings.toDouble() * analysisRate / actual.size
            assertTrue(abs(hz - 220) < 2, "Analysis received a pitch-scaled signal: $hz Hz")
        } finally {
            analyzer.release()
            Files.deleteIfExists(input)
        }
    }
}
