package com.music.bitchord.desktop

import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.model.ShelfItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopBrowseModelsTest {
    @Test
    fun podcastShowSearchHistoryOpensAPage() {
        val show = BrowseItem("MPSPP_show", "Show", "Podcast", null, BrowseType.OTHER)
        val entity = show.toSearchHistoryEntity()
        assertEquals(EntityType.OTHER, entity.entityType)
        assertEquals(show.browseId, entity.id)
        assertEquals(show.title, entity.title)
    }

    @Test
    fun namedBrowseTypesRetainTheirHistoryType() {
        listOf(BrowseType.ALBUM to EntityType.ALBUM, BrowseType.ARTIST to EntityType.ARTIST,
            BrowseType.PLAYLIST to EntityType.PLAYLIST).forEach { (browse, entity) ->
            assertEquals(entity, BrowseItem("id", "title", "subtitle", "art", browse).toSearchHistoryEntity().entityType)
        }
    }

    @Test
    fun shelfVideoRetainsBothVideoFlagsForPlayback() {
        val song = ShelfItem("Video", "Video • Artist • 3:45", "art", "video", null, isVideo = true).toSong()
        assertTrue(song.isVideo)
        assertTrue(song.isVideoOrigin)
        assertEquals("Artist", song.artist)
        assertEquals("3:45", song.durationText)
        assertEquals("video", song.videoId)
    }

    @Test
    fun shelfAudioKeepsItsExistingMetadataAndDefaults() {
        val song = ShelfItem("Track", "Song • Artist • 2:30", "art", "audio", null).toSong()
        assertFalse(song.isVideo)
        assertFalse(song.isVideoOrigin)
        assertEquals("Artist", song.artist)
        assertEquals("2:30", song.durationText)
        assertEquals("art", song.thumbnailUrl)
    }
}
