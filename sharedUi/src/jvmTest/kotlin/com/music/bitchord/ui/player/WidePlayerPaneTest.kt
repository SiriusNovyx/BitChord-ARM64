package com.music.bitchord.ui.player

import com.music.bitchord.data.settings.PlayerLayout
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidePlayerPaneTest {
    @Test fun uprightPhonesAndTabletsRetainThePortraitPlayer() {
        assertFalse(landscapePlayerAvailable(360.dp, 800.dp))
        assertFalse(landscapePlayerAvailable(800.dp, 1280.dp))
        assertFalse(landscapePlayerAvailable(800.dp, 800.dp))
    }

    @Test fun landscapePhonesAndTabletsUseTheWidePlayerWhenBothColumnsFit() {
        assertTrue(landscapePlayerAvailable(800.dp, 360.dp))
        assertTrue(landscapePlayerAvailable(1280.dp, 800.dp))
        assertFalse(landscapePlayerAvailable(559.dp, 300.dp))
        assertTrue(landscapePlayerAvailable(560.dp, 300.dp))
    }

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
