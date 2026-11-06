package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual FFmpeg input and engine publication, with a non-playing output for deterministic inspection. */
class DesktopAudioDiagnosticsTest {
    private class Output : DesktopAudioOutput {
        override var format = DesktopPcmFormat(44_100, 2, 4, isFloat = true)
        override var gain = 1f
        override var isOpen = false
        override val deviceName = "Diagnostic fixture"
        override val route = "USB"
        override val bufferBytes = 0
        override fun isUsingSelection(id: String) = isOpen
        override fun open(requested: DesktopPcmFormat) = Result.success(requested).also {
            format = requested; isOpen = true
        }
        override fun write(samples: FloatArray, count: Int) = 0
        override fun framesPlayed() = 0L
        override fun pause() = Unit
        override fun resume() = Unit
        override fun flush() = Unit
        override fun close() { isOpen = false }
    }

    @Test fun `decoded stream diagnostics retain the owner independently of unsupported AutoMix`() {
        withDecodedTrack { engine, song ->
            val snapshot = assertNotNull(engine.activeDiagnostics())
            assertEquals(song.videoId, snapshot.mediaId,
                "The player rejects all real decoder observations when ownership is omitted")
            assertNotNull(snapshot.takeIf { it.mediaId == song.videoId })
            assertEquals(44_100, snapshot.sampleRateHz)
            assertEquals(16, snapshot.bitDepth)
            assertEquals(2, snapshot.channels)
            assertEquals("USB", snapshot.audioDiagnostics.outputRoute)
            assertEquals("pcm_s16le", snapshot.audioDiagnostics.observedCodec)
        }
    }

    @Test fun `loading a replacement cannot reuse the previous decoder observation`() {
        withDecodedTrack { engine, song ->
            assertEquals(song.videoId, assertNotNull(engine.activeDiagnostics()).mediaId)
            val next = song.copy(videoId = "replacement-owner")
            engine.load(next, playWhenReady = false)
            val initial = engine.activeDiagnostics()
            if (initial != null) assertEquals(next.videoId, initial.mediaId)
            awaitDecoded(engine)
            assertEquals(next.videoId, assertNotNull(engine.activeDiagnostics()).mediaId)
        }
    }

    @Test fun `seeking preserves valid observations and release retires them`() {
        withDecodedTrack { engine, song ->
            val seeks = engine.state.value.seeks
            engine.seekTo(100)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (engine.state.value.seeks == seeks && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(engine.state.value.seeks > seeks)
            val observed = assertNotNull(engine.activeDiagnostics())
            assertEquals(song.videoId, observed.mediaId)
            assertEquals(44_100, observed.sampleRateHz)
            engine.release()
            assertNull(engine.activeDiagnostics(), "Retired playback must not retain an active observation")
        }
    }

    private fun withDecodedTrack(block: (DesktopPlaybackEngine, Song) -> Unit) {
        val file = Files.createTempFile("bitchord-audio-observation-", ".wav")
        val samples = 44_100 * 2
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(2).putInt(44_100).putInt(176_400).putShort(4).putShort(16)
        bytes.put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { bytes.putShort(if (it % 80 < 40) 1200 else -1200) }
        Files.write(file, bytes.array())
        val song = Song("diagnostic-owner", "Diagnostic", "Fixture", null, isVideoOrigin = true)
        val engine = DesktopPlaybackEngine({}, {}, Output(),
            { Result.success(DesktopLiveResolution(DesktopStream(file.toString(), sourceId = "fixture-addon"))) },
            null, false)
        try {
            engine.load(song)
            awaitDecoded(engine)
            block(engine, song)
        } finally { engine.shutdown(); Files.deleteIfExists(file) }
    }

    private fun awaitDecoded(engine: DesktopPlaybackEngine) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (engine.state.value.streamFormat == null && engine.state.value.error == null && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(engine.state.value.error == null, engine.state.value.error)
        assertNotNull(engine.state.value.streamFormat, "Actual FFmpeg observation must reach playback state")
    }
}
