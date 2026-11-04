package com.music.bitchord.desktop

import com.music.bitchord.data.AudioDiagnostics
import com.music.bitchord.data.AtmosAvailability
import com.music.bitchord.data.AtmosPlayback
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.sources.StreamFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DesktopAtmosDiagnosticsTest {
    @Test
    fun `core decoder availability does not prove an Atmos rendition is available`() {
        assertEquals(AtmosAvailability.UNKNOWN, AudioDiagnostics(observedCodec = "eac3", decoderAvailable = true).availability)
        assertEquals(AtmosAvailability.UNAVAILABLE,
            AudioDiagnostics(sourceDeclaredAtmos = false, observedCodec = "opus", decoderAvailable = true).availability)
    }
    @Test
    fun `a source declaration and core decoder cannot confirm Atmos output`() {
        val evidence = AudioDiagnostics(
            preferenceEnabled = true,
            sourceDeclaredAtmos = true,
            observedCodec = "audio/eac3",
            decoderAvailable = true,
            outputRoute = "WASAPI shared PCM",
            outputChannels = 2,
        )
        assertEquals(AtmosAvailability.AVAILABLE, evidence.availability)
        assertEquals(AtmosPlayback.NOT_CONFIRMED, evidence.playback)
        assertFalse(evidence.observedJoc)
    }

    @Test
    fun `JOC input without output evidence stays unknown`() {
        val evidence = AudioDiagnostics(observedCodec = "audio/eac3-joc", decoderAvailable = true)
        assertEquals(AtmosPlayback.UNKNOWN, evidence.playback)
        assertEquals(AtmosAvailability.AVAILABLE, evidence.availability)
        assertEquals(AtmosPlayback.CONFIRMED, evidence.copy(objectOutputConfirmed = true).playback)
    }

    @Test
    fun `unsupported unknown and stereo remain distinct`() {
        assertEquals(AtmosAvailability.UNKNOWN, AudioDiagnostics().availability)
        assertEquals(AtmosPlayback.UNKNOWN, AudioDiagnostics().playback)
        assertEquals(AtmosAvailability.UNAVAILABLE, AudioDiagnostics(decoderAvailable = false).availability)
        assertEquals(AtmosPlayback.NOT_CONFIRMED, AudioDiagnostics(observedCodec = "eac3-joc", decoderAvailable = false).playback)
        assertEquals(AtmosPlayback.NOT_CONFIRMED, AudioDiagnostics(observedCodec = "opus").playback)
        assertEquals(AtmosPlayback.UNKNOWN, AudioDiagnostics(observedCodec = "unknown-codec").playback)
    }

    @Test
    fun `preference remains independent of an already open stream`() {
        val current = AudioDiagnostics(preferenceEnabled = false, observedCodec = "eac3-joc", decoderAvailable = true)
        assertEquals(false, current.preferenceEnabled)
        assertEquals(AtmosAvailability.AVAILABLE, current.availability)
        assertEquals(AtmosPlayback.UNKNOWN, current.playback)
        assertEquals(AtmosPlayback.UNKNOWN, current.copy(sourceDeclaredAtmos = true).playback)
    }

    @Test
    fun `fallback replaces Atmos evidence rather than inheriting it`() {
        val id = "atmos-diagnostics-fallback"
        NerdStats.onTrackTransition(id)
        val generation = NerdStats.currentGeneration
        NerdStats.onSourceStream(id, StreamFormat(codec = "eac3-joc"))
        NerdStats.publish(NerdStats.Snapshot("audio/eac3-joc", 768, 48_000, 6, mediaId = id), generation)
        NerdStats.clearDeclared(id)
        NerdStats.publish(NerdStats.Snapshot("audio/opus", 160, 48_000, 2, mediaId = id), generation)
        assertEquals(AtmosPlayback.NOT_CONFIRMED, NerdStats.current.value?.audioDiagnostics?.playback)
        assertNull(NerdStats.current.value?.audioDiagnostics?.sourceDeclaredAtmos)
        NerdStats.onTrackTransition("next-stereo")
        assertNull(NerdStats.current.value)
    }
}
