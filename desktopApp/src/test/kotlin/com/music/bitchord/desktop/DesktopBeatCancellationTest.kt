package com.music.bitchord.desktop

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.music.bitchord.playback.smart.BeatTracker
import com.music.bitchord.playback.smart.MelSpectrogram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopBeatCancellationTest {
    @Test fun `already interrupted work does not open a model`() {
        DesktopAnalysisRuntime.ensureStarted()
        assertTrue(DesktopAnalysisRuntime.available, "This regression requires the packaged native analyser")
        var modelRequests = 0
        val tracker = BeatTracker(
            modelPath = { modelRequests++; DesktopAnalysisRuntime.modelPath(BeatTracker.MODEL_ASSET) },
            inferenceThreads = { 1 },
        )
        try {
            Thread.currentThread().interrupt()
            assertNull(tracker.track(FloatArray(MelSpectrogram.sampleRate.toInt())))
            assertEquals(0, modelRequests, "obsolete work must stop before model initialization")
        } finally {
            Thread.interrupted()
            tracker.release()
        }
    }

    @Test fun `interrupted inference does not process any remaining chunks`() {
        DesktopAnalysisRuntime.ensureStarted()
        assertTrue(DesktopAnalysisRuntime.available, "This regression requires the packaged native analyser")
        val path = DesktopAnalysisRuntime.modelPath(BeatTracker.MODEL_ASSET)
        val tracker = BeatTracker({ path }, { 1 })
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            OrtEnvironment.getEnvironment().createSession(path, options).use { session ->
                val frames = BeatTracker.CHUNK_FRAMES * 3
                val spectrogram = MelSpectrogram.Spectrogram(FloatArray(frames * 128), frames, 128)
                val beat = FloatArray(frames) { Float.NaN }
                val downbeat = FloatArray(frames) { Float.NaN }
                // Exercise the actual chunk loop, with a real ONNX session, independently of
                // the earlier cancellation checks in track().
                val infer = BeatTracker::class.java.getDeclaredMethod("infer", OrtSession::class.java,
                    MelSpectrogram.Spectrogram::class.java, FloatArray::class.java, FloatArray::class.java)
                infer.isAccessible = true
                try {
                    Thread.currentThread().interrupt()
                    assertFalse(infer.invoke(tracker, session, spectrogram, beat, downbeat) as Boolean)
                    assertTrue(beat.all(Float::isNaN) && downbeat.all(Float::isNaN),
                        "cancelled work must not publish logits from later native chunks")
                } finally { Thread.interrupted() }
            }
        }
    }
}
