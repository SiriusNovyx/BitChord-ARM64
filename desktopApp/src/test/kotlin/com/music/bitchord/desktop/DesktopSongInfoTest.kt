package com.music.bitchord.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.music.bitchord.data.SongInfo
import com.music.bitchord.data.SongInfoRepository
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesktopSongInfoTest {
    private val song = Song("abcdefghijk", "Fixture title", "Fixture artist", null)

    @Test fun failedMetadataCanRetryAndDoneClosesTheMenu() {
        var requests = 0
        var dismissed = 0
        val fixture = Fixture({ song }, {
            requests++
            if (requests == 1) SongInfo(failed = true) else SongInfo(album = "Fresh retry album")
        }, { dismissed++ })
        try {
            fixture.click(DesktopStrings["song_info", "Song info"])
            assertTrue(fixture.text().contains(DesktopStrings["song_info_error", "Some information could not be loaded."]))
            fixture.click(DesktopStrings["song_info_retry", "Retry"])
            assertEquals(2, requests)
            assertTrue(fixture.text().contains("Fresh retry album"))
            fixture.click(DesktopStrings["done", "Done"])
            assertEquals(1, dismissed)
        } finally { fixture.close() }
    }

    @Test fun closingInfoCancelsItsPendingRequest() {
        var started = false
        var cancelled = false
        val fixture = Fixture({ song }, {
            started = true
            try { awaitCancellation() } finally { cancelled = true }
        })
        try {
            fixture.click(DesktopStrings["song_info", "Song info"])
            assertTrue(started)
            assertTrue(fixture.text().contains(DesktopStrings["loading", "Loading…"]))
        } finally { fixture.close() }
        assertTrue(cancelled, "Closing the menu must cancel its metadata work")
    }

    @Test fun aLatePreviousTrackResultCannotReplaceTheNewTracksInfo() {
        var selected by mutableStateOf(song)
        val late = CompletableDeferred<SongInfo>()
        val fixture = Fixture({ selected }, { requested ->
            if (requested.videoId == song.videoId) withContext(NonCancellable) { late.await() }
            else SongInfo(album = "Current track album")
        })
        try {
            fixture.click(DesktopStrings["song_info", "Song info"])
            selected = song.copy(videoId = "newtrack__2", title = "New track")
            fixture.render()
            fixture.click(DesktopStrings["song_info", "Song info"])
            assertTrue(fixture.text().contains("Current track album"))
            late.complete(SongInfo(album = "Obsolete track album"))
            fixture.render()
            assertTrue(fixture.text().contains("Current track album"))
            assertFalse(fixture.text().contains("Obsolete track album"))
        } finally { late.complete(SongInfo()); fixture.close() }
    }

    @Test fun sharedRepositoryKeepsLocalMetadataWithoutRequestingUploadStatistics() = runBlocking {
        val info = withTimeout(2_000) {
            SongInfoRepository.load(song.copy(localUri = "file:///C:/Music/fixture.flac", albumName = "Local album"))
        }
        assertEquals("Local album", info.album)
        assertFalse(info.failed)
        assertNull(info.views)
        assertNull(info.likes)
        assertNull(info.comments)
    }

    @Test fun songMenuLoadsInfoForTheExactLocalOrOnlineTrack() {
        for (local in listOf(true, false)) {
            val song = Song("abcdefghijk", "Fixture title", "Fixture artist", null,
                localUri = if (local) "file:///C:/Music/fixture.flac" else null)
            var requested: Song? = null
            var requests = 0
            val scene = ImageComposeScene(760, 1100, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme {
                    DesktopSongMenuFor(song, false, actions(), {}, null, null,
                        loadSongInfo = { requests++; requested = it; SongInfo(album = "Exact fixture album") },
                        onDismiss = {})
                }
            }
            try {
                repeat(3) { scene.render(it * 16_666_667L).close() }
                assertEquals(0, requests, "Opening a song menu must not fetch metadata")
                val action = assertNotNull(nodes(scene).firstOrNull {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                        text.text == DesktopStrings["song_info", "Song info"] } == true &&
                        it.config.getOrNull(SemanticsActions.OnClick) != null
                }, "The native song-info action is missing")
                assertTrue(action.config[SemanticsActions.OnClick].action!!.invoke())
                repeat(4) { scene.render((it + 3) * 16_666_667L).close() }
                assertEquals(song, requested)
                assertEquals(1, requests)
                assertTrue(text(scene).contains("Exact fixture album"))
            } finally { scene.close() }
        }
    }

    private fun actions() = DesktopSongActions(
        signedIn = false, disliked = false, downloaded = false, downloadInProgress = false,
        sleepTimerMinutes = null, sleepAfterTrack = false, onToggleDislike = {},
        onAddToPlaylist = {}, onDownload = {}, onRemoveDownload = {}, onStartRadio = {},
        onPlayNext = {}, onAddToQueue = {}, onOpenAlbum = {}, onOpenArtist = {},
        onSleepTimer = {}, onSleepAfterTrack = {}, onShare = {},
    )

    private inner class Fixture(song: () -> Song, load: suspend (Song) -> SongInfo, onDismiss: () -> Unit = {}) {
        private var frame = 0L
        private val scene = ImageComposeScene(760, 1100, Density(1f), coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                DesktopSongMenuFor(song(), false, actions(), {}, null, null, loadSongInfo = load, onDismiss = onDismiss)
            }
        }
        fun render() { repeat(4) { scene.render(++frame * 16_666_667L).close() } }
        fun click(label: String) {
            render()
            val node = assertNotNull(nodes(scene).firstOrNull {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null
            }, "Missing action: $label")
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
            render()
        }
        fun text() = text(scene)
        fun close() = scene.close()
    }

    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun text(scene: ImageComposeScene) = nodes(scene)
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
