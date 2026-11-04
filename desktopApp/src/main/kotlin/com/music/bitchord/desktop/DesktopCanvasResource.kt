package com.music.bitchord.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal suspend inline fun <T : AutoCloseable, R> withDesktopCanvasResource(
    resource: T,
    block: (T) -> R,
): R {
    try {
        return block(resource)
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { resource.close() }
    }
}
