package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.smart.AnalysisStore
import com.music.bitchord.playback.smart.TrackAnalysis
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopLoopMixProvenanceTest {
    @Test fun decoderPositionDescribesReturnedSamplesAfterIndexedSeekTrimming() {
        val path = java.nio.file.Files.createTempFile("bitchord-loop-seek-", ".wav")
        val rate = 8_000
        val frames = rate * 4
        val bytes = java.nio.ByteBuffer.allocate(44 + frames * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
        bytes.put("data".toByteArray()).putInt(frames * 4)
        repeat(frames) { i -> val v = (i % 1000).toShort(); bytes.putShort(v).putShort(v) }
        java.nio.file.Files.write(path, bytes.array())
        val decoder = DesktopAudioDecoder()
        try {
            decoder.open(path.toString(), requested = DesktopPcmFormat(rate, 2, 4, true)).getOrThrow()
            val targetFrame = 18_765L
            val targetUs = targetFrame * 1_000_000 / rate
            assertTrue(decoder.seek(targetUs - 50_000))
            // WAV normally seeks exactly. Reproduce an indexed container landing before its target.
            decoder.javaClass.getDeclaredField("trimBeforeUs").apply { isAccessible = true }.set(decoder, targetUs)
            val samples = assertNotNull(decoder.readSamples())
            assertEquals((targetFrame % 1000).toFloat() / 32768, samples.first(), 1e-6f)
            assertTrue(kotlin.math.abs(decoder.positionUs - targetUs) <= 1_000_000 / rate,
                "Returned samples start at $targetUs, but decoder reports ${decoder.positionUs}")
            val lastKnownPosition = decoder.positionUs
            for (clock in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                decoder.javaClass.getDeclaredField("streamTimeBase").apply { isAccessible = true }.setDouble(decoder, clock)
                assertNotNull(decoder.readSamples())
                assertNull(decoder.samplePositionUs, "A malformed source clock cannot authorize an exact LoopMix region")
                assertEquals(lastKnownPosition, decoder.positionUs, "Unknown clocks must not replace the last observed position")
            }
        } finally { decoder.close(); java.nio.file.Files.deleteIfExists(path) }
    }

    @Test fun localRecordingIdentityTracksFileReplacementAndDeletion() {
        val path = java.nio.file.Files.createTempFile("bitchord-recording-identity-", ".pcm")
        try {
            java.nio.file.Files.write(path, byteArrayOf(1, 2, 3, 4))
            val stream = DesktopStream(path.toString())
            val measured = assertNotNull(DesktopLoopMixRecording.capture(stream))
            assertTrue(measured.isCurrent())
            assertTrue(measured.matches(DesktopLoopMixRecording.capture(stream)))
            java.nio.file.Files.write(path, byteArrayOf(4, 3, 2, 1, 0))
            assertFalse(measured.isCurrent())
            assertFalse(measured.matches(DesktopLoopMixRecording.capture(stream)))
            java.nio.file.Files.delete(path)
            assertFalse(measured.isCurrent())
            assertNull(DesktopLoopMixRecording.capture(stream))
        } finally { java.nio.file.Files.deleteIfExists(path) }
    }

    @Test fun restoredAnalysisRemainsUsableWhileFreshLoopEvidenceIsRequested() {
        val song = Song("loop-cache-${UUID.randomUUID()}", "Generated cache", "Fixture", null, durationText = "1:36")
        val old = TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = song.videoId,
            duration = 96.0, bpm = 120.0, beatInterval = .5, beatConfidence = .9)
        AnalysisStore { DesktopTrackAnalyzer.analysisCacheHome() }.save(song.videoId, old)
        val asked = CountDownLatch(1)
        val analyzer = DesktopTrackAnalyzer(sourceOverride = { asked.countDown(); null })
        try {
            assertTrue(analyzer.analysisFor(song.videoId).isUsable)
            assertNull(analyzer.measuredSourceFor(song.videoId))
            analyzer.request(song, null, 96.0)
            assertEquals(1L, asked.count, "Ordinary cached analysis must stay cheap")
            val request = analyzer.javaClass.methods.find { it.name == "requestLoopEvidence" }
            assertNotNull(request, "LoopMix must be able to renew legacy cached evidence with unknown recording identity")
            request.invoke(analyzer, song, null, 96.0)
            assertTrue(asked.await(3, TimeUnit.SECONDS), "A restored cache must not permanently disable LoopMix")
            assertTrue(analyzer.analysisFor(song.videoId).isUsable, "Failed renewal must retain ordinary usable analysis")
        } finally { analyzer.release() }
    }

    @Test fun renewedSignedUrlMustIdentifyTheSameEncodedRevision() {
        fun media(extra: String = "", itag: String = "251", revision: String = "123") = DesktopStream(
            "https://rr1.googlevideo.com/videoplayback?id=recording&itag=$itag&lmt=$revision&clen=100&dur=96$extra", sourceId = "youtube")
        val measured = media("&expire=100&sig=old")
        assertTrue(sameLoopMixRecording(measured, media("&expire=200&sig=new")))
        assertFalse(sameLoopMixRecording(measured, media(itag = "140")))
        assertFalse(sameLoopMixRecording(measured, media(revision = "456")))
        assertFalse(sameLoopMixRecording(measured, media().copy(url = "https://addon.invalid/same-title")))
        assertFalse(sameLoopMixRecording(measured, media().copy(sourceId = "addon")))
        assertFalse(sameLoopMixRecording(null, media()))
    }
}
