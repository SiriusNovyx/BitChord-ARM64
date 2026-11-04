package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DesktopDiscordNativeAdaptationTest {
    private val song = Song("track", "Track", "Artist", null, albumName = "Album")

    @Test fun `presence quality follows this playback snapshot and its visibility switch`() {
        val state = DesktopPlaybackState(song = song, streamSourceId = "synthetic", streamFormat = DesktopStreamFormat(
            codec = "flac", kbps = 4608, sampleRateHz = 96_000, bitDepth = 24, channels = 2,
        ))
        assertEquals("Hi-Res Lossless · FLAC · 4608 kbps · 24-bit · 96 kHz · Stereo",
            desktopDiscordQualityLine(state, showAudioQuality = true))
        assertNull(desktopDiscordQualityLine(state, showAudioQuality = false))
        assertNull(desktopDiscordQualityLine(state.copy(streamFormat = null), showAudioQuality = true))
        assertNull(desktopDiscordQualityLine(state.copy(streamFormat = DesktopStreamFormat(
            codec = "opus", kbps = 160, sampleRateHz = 48_000, channels = 2,
        )), showAudioQuality = true))
    }

    @Test fun `IPC tooltip carries measured quality rather than the album name`() {
        val assets = desktopDiscordAssets(song.copy(thumbnailUrl = "https://art.example/cover.jpg"), "Lossless · FLAC")
        assertEquals("Lossless · FLAC", assets["large_text"]?.jsonPrimitive?.content)
        assertEquals("https://art.example/cover.jpg", assets["large_image"]?.jsonPrimitive?.content)
    }

    @Test fun `missing and local artwork leave the application icon as fallback`() {
        for (thumbnail in listOf(null, "file:///cover.jpg", "content://cover/1")) {
            val track = song.copy(thumbnailUrl = thumbnail)
            assertNull(desktopDiscordArtwork(track))
            val assets = desktopDiscordAssets(track, null)
            assertFalse("large_image" in assets)
            assertFalse("large_text" in assets)
        }
    }
}
