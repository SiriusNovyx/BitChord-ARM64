package com.music.bitchord.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesktopLocalPlaylistMenuTest {
    private val local = Song(
        videoId = "local:track", title = "Local track", artist = "Artist", thumbnailUrl = null,
        localUri = "file:///C:/Music/track.flac",
    )

    @Test fun localTrackCanOpenThePlaylistPickerWithoutCloudActions() {
        assertPlaylistAction(local, signedIn = false)
        assertPlaylistAction(local, signedIn = true)
    }

    @Test fun downloadedTrackKeepsItsFileIdentityWhenOpeningThePlaylistPicker() {
        assertPlaylistAction(local.copy(videoId = "abcdefghijk"), signedIn = true, downloaded = true)
    }

    @Test fun onlineTrackRetainsPlaylistAndAccountActions() {
        val song = local.copy(videoId = "abcdefghijk", localUri = null)
        assertPlaylistAction(song, signedIn = true)
    }

    private fun assertPlaylistAction(song: Song, signedIn: Boolean, downloaded: Boolean = false) {
        var picked: Song? = null
        var dismissed = 0
        val actions = DesktopSongActions(
            signedIn = signedIn, disliked = false, downloaded = downloaded, downloadInProgress = false,
            sleepTimerMinutes = null, sleepAfterTrack = false,
            onToggleDislike = { error("Unexpected cloud rating") },
            onAddToPlaylist = { picked = it },
            onDownload = { error("Unexpected download") }, onRemoveDownload = {},
            onStartRadio = {}, onPlayNext = {}, onAddToQueue = {}, onOpenAlbum = {}, onOpenArtist = {},
            onSleepTimer = {}, onSleepAfterTrack = {}, onShare = { error("Unexpected cloud share") },
        )
        val scene = ImageComposeScene(720, 900, Density(1f), coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                DesktopSongMenuFor(song = song, liked = false, actions = actions, onToggleLike = {},
                    onRevertToOriginal = null, onUpgradeQuality = null, onDismiss = { dismissed++ })
            }
        }
        try {
            repeat(3) { scene.render(it * 16_666_667L).close() }
            val nodes = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
            fun hasLabel(label: String) = nodes.any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true }
            val playlistLabel = DesktopStrings["add_to_playlist", "Add to playlist"]
            val playlist = assertNotNull(nodes.firstOrNull { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == playlistLabel } == true &&
                    node.config.getOrNull(SemanticsActions.OnClick) != null
            }, "Playlist action is absent for ${song.videoId}, local=${song.localUri != null}, signedIn=$signedIn")
            if (song.localUri != null) {
                assertFalse(hasLabel("Like"))
                assertFalse(hasLabel("Dislike"))
                assertFalse(hasLabel(DesktopStrings["share", "Share"]))
                assertFalse(hasLabel(DesktopStrings["download", "Download"]))
            } else {
                assertTrue(hasLabel("Like"))
                assertTrue(hasLabel("Dislike"))
            }
            assertTrue(playlist.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals(song, picked)
            assertEquals(song.localUri, picked?.localUri)
            assertEquals(1, dismissed)
        } finally {
            scene.close()
        }
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
