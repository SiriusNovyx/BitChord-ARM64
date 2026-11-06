package com.music.bitchord.desktop

import com.music.bitchord.data.model.HomeFeed
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.net.UnknownHostException

class DesktopHomeFeedTest {
    private fun shelf(title: String, id: String) = HomeShelf(title, listOf(ShelfItem(id, "", null, id, null)))

    @Test fun `each refresh revalidates core recent and supplements and replaces continuation`() = runBlocking {
        val calls = mutableListOf<String>()
        var revision = 1
        suspend fun fetch() = fetchDesktopHome(true,
            coreFetch = { calls += "core"; Result.success(HomeFeed(listOf(shelf("Core", "core-$revision")), "page-$revision")) },
            recentFetch = { calls += "recent"; Result.success(shelf("Recently played", "recent-$revision")) },
            supplementIds = listOf("new", "charts"),
            supplementFetch = { calls += it; Result.success(listOf(shelf(it, "$it-$revision"))) },
        )
        val first = fetch()
        revision++
        val second = fetch()
        assertEquals(listOf("Recently played", "Core", "new", "charts"), second.shelves.map { it.title })
        assertEquals("core-1", first.shelves[1].items.single().videoId)
        assertEquals("core-2", second.shelves[1].items.single().videoId)
        assertEquals("page-2", second.continuation)
        assertEquals(mapOf("core" to 2, "recent" to 2, "new" to 2, "charts" to 2), calls.groupingBy { it }.eachCount())
    }

    @Test fun `signed out skips recent and failed core cannot masquerade as updated`() = runBlocking {
        assertFailsWith<UnknownHostException> {
            fetchDesktopHome(false,
                coreFetch = { Result.failure(UnknownHostException()) },
                recentFetch = { error("signed out recent request") },
                supplementIds = emptyList(), supplementFetch = { error("no supplements") },
            )
        }
        Unit
    }
}
