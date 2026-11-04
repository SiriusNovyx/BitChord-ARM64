package com.music.bitchord.desktop

import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song

internal fun BrowseItem.toSearchHistoryEntity() = SearchHistoryEntity(
    id = browseId,
    title = title,
    subtitle = subtitle,
    artworkUrl = thumbnailUrl,
    entityType = when (type) {
        BrowseType.ALBUM -> EntityType.ALBUM
        BrowseType.ARTIST -> EntityType.ARTIST
        BrowseType.PLAYLIST -> EntityType.PLAYLIST
        BrowseType.OTHER -> EntityType.OTHER
    },
)

internal fun ShelfItem.toSong(): Song {
    val parts = subtitle.split(" • ", " · ", " | ").filter(String::isNotBlank)
    val artist = parts.firstOrNull {
        !it.matches(SHELF_DURATION) &&
            it.lowercase() !in SHELF_TYPE_WORDS &&
            !it.matches(SHELF_TALLY)
    } ?: "Unknown Artist"
    return Song(
        videoId = videoId.orEmpty(),
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = parts.firstOrNull { it.matches(SHELF_DURATION) },
        isVideo = isVideo,
    )
}

private val SHELF_DURATION = Regex("""\d{1,2}:\d{2}(?::\d{2})?""")
private val SHELF_TALLY = Regex("[0-9,.]+\\s*(?:views?|likes?|songs?)", RegexOption.IGNORE_CASE)
private val SHELF_TYPE_WORDS = setOf("song", "video", "album", "ep", "single", "playlist", "mix")
