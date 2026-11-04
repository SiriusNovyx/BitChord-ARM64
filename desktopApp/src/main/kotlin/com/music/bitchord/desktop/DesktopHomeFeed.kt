package com.music.bitchord.desktop

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.HomeFeed
import com.music.bitchord.data.model.withoutRepeatsOf
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

internal data class DesktopHomeContent(val shelves: List<HomeShelf>, val continuation: String?)

/** Each endpoint is revalidated; no stream, decoder or audio cache is invalidated by a feed refresh. */
internal suspend fun fetchDesktopHome(
    signedIn: Boolean,
    coreFetch: suspend () -> Result<HomeFeed> = { DesktopSearchClient.home() },
    recentFetch: suspend () -> Result<HomeShelf?> = { YtMusicRepository.homeRecentlyPlayed() },
    supplementIds: List<String> = DesktopSearchClient.HOME_SUPPLEMENT_BROWSE_IDS,
    supplementFetch: suspend (String) -> Result<List<HomeShelf>> = { DesktopSearchClient.homeSupplement(it) },
): DesktopHomeContent = coroutineScope {
    val core = async { coreFetch().getOrThrow() }
    val recent = async {
        if (signedIn) withTimeoutOrNull(12_000) { recentFetch().getOrNull() } else null
    }
    val supplements = supplementIds.map { browseId ->
        async { withTimeoutOrNull(12_000) { supplementFetch(browseId).getOrNull() }.orEmpty() }
    }
    val feed = core.await()
    val shelves = mutableListOf<HomeShelf>()
    val headings = HashSet<String>()
    fun append(rows: List<HomeShelf>) {
        rows.withoutRepeatsOf(shelves).forEach { row ->
            if (row.items.isNotEmpty() && headings.add(row.title.lowercase(java.util.Locale.ROOT))) shelves += row
        }
    }
    recent.await()?.let { append(listOf(it)) }
    append(feed.shelves)
    supplements.forEach { append(it.await()) }
    DesktopHomeContent(shelves, feed.continuation)
}
