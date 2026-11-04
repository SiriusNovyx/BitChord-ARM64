package com.music.bitchord.playback

/** Returns the sibling's base key; the caller appends the request's watch suffix. */
internal fun completePlaybackCacheSibling(
    videoId: String,
    preferred: String,
    watchRead: Boolean,
    hasCachedSpans: (String) -> Boolean,
    isOffline: () -> Boolean,
    isComplete: (String) -> Boolean,
    isKeptOrigin: (String) -> Boolean,
): String? {
    val suffix = if (watchRead) "#watch" else ""
    if (hasCachedSpans(preferred + suffix)) return null
    val siblings = listOf(videoId, "$videoId#alt", "$videoId#alt-noatmos")
        .filter { it != preferred }
    if (isOffline()) return siblings.firstOrNull { isComplete(it + suffix) }
    if (preferred != videoId) return null
    return siblings.firstOrNull { key -> isComplete(key + suffix) && isKeptOrigin(key + suffix) }
}
