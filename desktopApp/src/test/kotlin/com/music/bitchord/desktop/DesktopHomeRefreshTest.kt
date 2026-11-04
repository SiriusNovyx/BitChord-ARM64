package com.music.bitchord.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopHomeRefreshTest {
    @Test fun `switching profile or renewing a session retires otherwise fresh content`() = runBlocking {
        var calls = 0
        val refresh = DesktopHomeRefresh(this, { 0L }) { _: String -> ++calls }
        assertTrue(refresh.request(desktopHomeIdentity(true, "account", "one", 1)))
        yield()
        assertTrue(refresh.request(desktopHomeIdentity(true, "account", "two", 1)))
        assertNull(refresh.state.value.content, "A different channel must not retain another channel's private shelves")
        yield()
        assertTrue(refresh.request(desktopHomeIdentity(true, "account", "two", 2)))
        yield()
        assertEquals(3, calls)
    }

    @Test fun `retiring Home clears saved content and freshness before source reenables`() = runBlocking {
        var calls = 0
        val pending = CompletableDeferred<Int>()
        val refresh = DesktopHomeRefresh(this, { 0L }) { _: String -> if (++calls == 2) pending.await() else calls }
        refresh.request("account")
        yield()
        assertEquals(1, refresh.state.value.content)
        refresh.request("account", force = true)
        yield()
        refresh.close()
        assertNull(refresh.state.value.content)
        assertFalse(refresh.state.value.refreshing)
        pending.complete(2)
        yield()
        assertNull(refresh.state.value.content, "A late answer from a disabled source must not republish its feed")
        assertTrue(refresh.request("account"), "Reenabling must fetch rather than reuse the retired freshness timestamp")
        yield()
        assertEquals(3, refresh.state.value.content)
    }

    @Test fun `navigation reuses fresh content and stale revalidation fetches once`() = runBlocking {
        var now = 0L
        var requests = 0
        val refresh = DesktopHomeRefresh(this, { now }, freshForMs = 1_000, retryAfterMs = 100) { _: String -> ++requests }
        assertTrue(refresh.request("account"))
        yield()
        assertEquals(1, refresh.state.value.content)
        now = 999
        assertFalse(refresh.request("account"))
        now = 1_000
        assertTrue(refresh.request("account"))
        yield()
        assertEquals(2, refresh.state.value.content)
        assertEquals(2, requests)
    }

    @Test fun `manual refresh keeps the old content and deduplicates concurrent triggers`() = runBlocking {
        val answer = CompletableDeferred<Int>()
        var calls = 0
        val refresh = DesktopHomeRefresh(this, { 0L }) { _: String -> if (++calls == 1) 7 else answer.await() }
        refresh.request("account")
        yield()
        assertTrue(refresh.request("account", force = true))
        assertEquals(7, refresh.state.value.content)
        assertTrue(refresh.state.value.refreshing)
        assertFalse(refresh.request("account", force = true))
        answer.complete(8)
        yield()
        assertEquals(8, refresh.state.value.content)
        assertFalse(refresh.state.value.refreshing)
        assertEquals(2, calls)
    }

    @Test fun `offline refresh retains data and automatic retries observe backoff`() = runBlocking {
        var now = 0L
        var offline = false
        val refresh = DesktopHomeRefresh(this, { now }, freshForMs = 1_000, retryAfterMs = 100) { _: String ->
            if (offline) throw java.net.UnknownHostException("synthetic offline") else 4
        }
        refresh.request("account")
        yield()
        offline = true
        now = 1_000
        refresh.request("account")
        yield()
        assertEquals(4, refresh.state.value.content)
        assertEquals(HomeRefreshResult.OFFLINE, refresh.state.value.result)
        assertFalse(refresh.state.value.refreshing)
        now = 1_099
        assertFalse(refresh.request("account"))
        now = 1_100
        assertTrue(refresh.request("account"))
        yield()
    }

    @Test fun `switching accounts clears old private content and rejects late old answers`() = runBlocking {
        val old = CompletableDeferred<Int>()
        val refresh = DesktopHomeRefresh(this, { 0L }) { account: String -> if (account == "old") old.await() else 9 }
        refresh.request("old")
        assertTrue(refresh.request("new"))
        assertNull(refresh.state.value.content)
        yield()
        old.complete(1)
        yield()
        assertEquals(9, refresh.state.value.content)
        assertEquals("new", refresh.state.value.identity)
    }

    @Test fun `cancelling the owner clears loading and permits a later retry`() = runBlocking {
        val owner = launch {
            val refresh = DesktopHomeRefresh(this, { 0L }) { _: String -> CompletableDeferred<Int>().await() }
            refresh.request("account")
            try { kotlinx.coroutines.awaitCancellation() } finally { refresh.close() }
        }
        yield()
        owner.cancelAndJoin()
        assertTrue(owner.isCompleted)
    }
}
