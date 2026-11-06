package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackCacheSiblingTest {
    @Test
    fun completeWatchPreferredIsNotRedirectedByCompletePhoneSibling() {
        val entries = mapOf("v#watch" to complete, "v#alt" to complete)
        assertNull(select(entries))
    }

    @Test
    fun partialWatchPreferredIsNeverMixedWithCompleteSibling() {
        val entries = mapOf("v#watch" to partial, "v#alt#watch" to complete, "v#alt" to complete)
        assertNull(select(entries, offline = { error("Preferred bytes must be checked before connectivity") }))
    }

    @Test
    fun emptyWatchCacheDoesNotBorrowCompletePhoneEntries() {
        val entries = mapOf("v" to complete, "v#alt" to complete, "v#alt-noatmos" to complete)
        assertNull(select(entries))
    }

    @Test
    fun offlineWatchFindsCompleteAlternateWatchCopyDespitePartialPhonePreferred() {
        val entries = mapOf("v" to partial, "v#alt#watch" to complete)
        assertEquals("v#alt", select(entries))
    }

    @Test
    fun offlineWatchCanMigrateBetweenAtmosSettings() {
        val entries = mapOf("v#alt-noatmos#watch" to complete)
        assertEquals("v#alt-noatmos", select(entries, preferred = "v#alt"))
    }

    @Test
    fun incompleteWatchSiblingsAreNotReused() {
        val entries = mapOf("v#alt#watch" to partial, "v#alt-noatmos#watch" to partial)
        assertNull(select(entries))
    }

    @Test
    fun phoneSelectionIgnoresWatchCopiesAndKeepsOfflineMigration() {
        val entries = mapOf("v#watch" to complete, "v#alt#watch" to complete, "v#alt" to complete)
        assertEquals("v#alt", select(entries, watchRead = false))
    }

    @Test
    fun onlinePlainWatchReusesCompleteKeptOriginInWatchNamespace() {
        val entries = mapOf("v#alt#watch" to complete, "v#alt" to foreign)
        assertEquals("v#alt", select(entries, offline = { false }))
    }

    @Test
    fun onlinePlainWatchDoesNotBorrowPhoneOriginForForeignWatchCopy() {
        val entries = mapOf("v#alt#watch" to foreign, "v#alt" to complete)
        assertNull(select(entries, offline = { false }))
    }

    @Test
    fun onlineSourcePreferenceDoesNotFallBackToPlainOrOtherAtmosSetting() {
        val entries = mapOf("v#watch" to complete, "v#alt-noatmos#watch" to complete)
        assertNull(select(entries, preferred = "v#alt", offline = { false }))
    }

    @Test
    fun partialPhonePreferredAlsoAvoidsConnectivityAndSiblingMixing() {
        val entries = mapOf("v" to partial, "v#alt" to complete)
        assertNull(select(entries, watchRead = false, offline = { error("Preferred bytes must win") }))
    }

    private fun select(
        entries: Map<String, Entry>,
        preferred: String = "v",
        watchRead: Boolean = true,
        offline: () -> Boolean = { true },
    ): String? = completePlaybackCacheSibling(
        videoId = "v",
        preferred = preferred,
        watchRead = watchRead,
        hasCachedSpans = { entries[it]?.hasSpans == true },
        isOffline = offline,
        isComplete = { entries[it]?.complete == true },
        isKeptOrigin = { entries[it]?.keptOrigin == true },
    )

    private data class Entry(val hasSpans: Boolean, val complete: Boolean, val keptOrigin: Boolean)

    private val complete = Entry(hasSpans = true, complete = true, keptOrigin = true)
    private val partial = Entry(hasSpans = true, complete = false, keptOrigin = true)
    private val foreign = Entry(hasSpans = true, complete = true, keptOrigin = false)
}
