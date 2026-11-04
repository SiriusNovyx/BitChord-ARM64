package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class DesktopDiscordTimelineTest {
    private val song = Song("track", "Track", "Artist", null)

    @Test fun `paused song stays visible without a running clock`() {
        val state = DesktopPlaybackState(song = song, isPlaying = false, positionMs = 151_000)
        assertEquals("Paused playback · Artist", desktopDiscordPlaybackLine(state, "Paused playback", "Buffering"))
        assertNull(desktopDiscordTimeline(state, 10_000, 250_000))
    }

    @Test fun `resuming restores timestamps while buffering holds the clock`() {
        val playing = DesktopPlaybackState(song = song, isPlaying = true)
        assertEquals("Artist", desktopDiscordPlaybackLine(playing, "Paused playback", "Buffering"))
        val clock = desktopDiscordTimeline(playing, 10_000, 250_000)!!
        assertEquals(10_000L, clock["start"]!!.jsonPrimitive.long)
        assertEquals(250_000L, clock["end"]!!.jsonPrimitive.long)
        val buffering = playing.copy(awaitingAudio = true)
        assertEquals("Buffering · Artist", desktopDiscordPlaybackLine(buffering, "Paused playback", "Buffering"))
        assertNull(desktopDiscordTimeline(buffering, 10_000, 250_000))
    }

    @Test fun `empty playback retains the previous track as paused but startup has no activity`() {
        val empty = DesktopPlaybackState()
        assertNull(desktopDiscordPlaybackState(empty, null))
        val previous = DesktopPlaybackState(song = song, isPlaying = true, positionMs = 151_000)
        val retained = desktopDiscordPlaybackState(empty, previous)!!
        assertEquals(song, retained.song)
        assertEquals(151_000L, retained.positionMs)
        assertFalse(retained.isPlaying)
        assertEquals(previous, desktopDiscordPlaybackState(previous, retained))
    }

    @Test fun `seek refreshes presence while normal position reports do not`() {
        val first = DesktopPlaybackState(song = Song(videoId = "test", title = "Track", artist = "Artist",
            thumbnailUrl = null, fromAutoplay = false),
            isPlaying = true, durationMs = 240_000, positionMs = 102_000, seeks = 1)
        val original = discordPlaybackRevision(first, 1f)
        assertEquals(original, discordPlaybackRevision(first.copy(positionMs = 103_000), 1f))
        val sought = first.copy(positionMs = 151_000, seeks = 2, awaitingAudio = true)
        assertNotEquals(original, discordPlaybackRevision(sought, 1f))
        assertNotEquals(discordPlaybackRevision(sought, 1f),
            discordPlaybackRevision(sought.copy(awaitingAudio = false), 1f))
        assertNotEquals(original, discordPlaybackRevision(first.copy(positionMs = 50_000, seeks = 3), 1f))
    }
}
