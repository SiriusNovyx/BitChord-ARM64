package com.music.bitchord.desktop

import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.model.SearchHistoryEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesktopSearchHistoryTest {
    @Test
    fun persistedPodcastShowsAreCorrectedBeforeRenderingAndAfterRecording() {
        assertEquals("com.music.bitchord.desktop.DesktopPortablePreferencesFactory", System.getProperty("java.util.prefs.PreferencesFactory"))
        assertFalse(System.getProperty("bitchord.dataDir").isNullOrBlank())
        DesktopPersistence().saveString("search_entities", """[
            {"id":"MPSPP_show","title":"Show","subtitle":"Podcast","artworkUrl":null,"entityType":"TRACK"},
            {"id":"episode","title":"Episode","subtitle":"Podcast","artworkUrl":null,"entityType":"TRACK"}
        ]""")
        assertEquals(EntityType.OTHER, DesktopSearchHistory.recent.value.first().entityType)
        assertEquals(EntityType.TRACK, DesktopSearchHistory.recent.value[1].entityType)
        DesktopSearchHistory.record(SearchHistoryEntity("MPSPP_second", "Show 2", "Podcast", null, EntityType.TRACK))
        assertEquals(EntityType.OTHER, DesktopSearchHistory.recent.value.first().entityType)
        assertEquals(listOf("MPSPP_second", "MPSPP_show", "episode"), DesktopSearchHistory.recent.value.map { it.id })
        DesktopSearchHistory.remove("MPSPP_second")
        assertEquals(2, DesktopSearchHistory.recent.value.size)
    }
}
