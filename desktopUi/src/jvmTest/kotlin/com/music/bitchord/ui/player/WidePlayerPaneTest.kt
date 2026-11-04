package com.music.bitchord.ui.player

import com.music.bitchord.data.settings.PlayerLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class WidePlayerPaneTest {
    @Test fun modernMainDisplaysLyricsWithoutChangingTheRequestedPane() {
        val requested = PlayerPane.Main
        assertEquals(PlayerPane.Lyrics, landscapePlayerPane(PlayerLayout.Modern, requested))
        assertEquals(PlayerPane.Main, requested)
    }

    @Test fun classicMainDisplaysPlaybackControls() {
        assertEquals(PlayerPane.Main, landscapePlayerPane(PlayerLayout.Classic, PlayerPane.Main))
    }

    @Test fun queueRemainsVisibleInEitherLayout() {
        PlayerLayout.entries.forEach {
            assertEquals(PlayerPane.Queue, landscapePlayerPane(it, PlayerPane.Queue))
        }
    }

    @Test fun explicitlyOpenedLyricsRemainVisibleInEitherLayout() {
        PlayerLayout.entries.forEach {
            assertEquals(PlayerPane.Lyrics, landscapePlayerPane(it, PlayerPane.Lyrics))
        }
    }
}
