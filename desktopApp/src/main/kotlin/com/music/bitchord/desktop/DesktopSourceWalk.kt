package com.music.bitchord.desktop

import kotlinx.coroutines.CancellationException
import java.io.IOException

internal class DesktopSourceHttpException(val status: Int) : Exception("HTTP $status")

private val Throwable.sourceHttpStatus: Int?
    get() = (this as? DesktopSourceHttpException)?.status
        ?: Regex("(?:^HTTP |returned HTTP )(\\d{3})").find(message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

internal fun Throwable.isProviderWideSourceFailure(): Boolean =
    this is IOException || this is DesktopAddonUnavailable ||
        sourceHttpStatus.let { it == 401 || it == 403 || it == 429 || (it != null && it >= 500) }

internal class DesktopSourceAvailability(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val blocked = mutableMapOf<String, Pair<DesktopSourceConfig, Long>>()

    @Synchronized
    fun canAttempt(config: DesktopSourceConfig): Boolean {
        if (!config.enabled || !config.isComplete || config.kind == DesktopSourceKind.SPOTIFY) return false
        val entry = blocked[config.id] ?: return true
        if (entry.first != config || nowMs() >= entry.second) {
            blocked.remove(config.id)
            return true
        }
        return false
    }

    @Synchronized
    fun unavailable(config: DesktopSourceConfig, rejected: Boolean = false) {
        blocked[config.id] = config to if (rejected) Long.MAX_VALUE else nowMs() + 30_000
    }

    @Synchronized
    fun recovered(config: DesktopSourceConfig) { blocked.remove(config.id) }

    fun recordFailure(config: DesktopSourceConfig, failure: Throwable) {
        if (failure.isProviderWideSourceFailure()) {
            unavailable(config, rejected = failure.sourceHttpStatus == 401 || failure.sourceHttpStatus == 403)
        }
    }
}

internal fun ownedSourceFallbackConfigs(
    available: List<DesktopSourceConfig>,
    selectedId: String,
    pickedAddon: Boolean,
): List<DesktopSourceConfig> = available.inSourceOrder().filter {
    it.id != selectedId && !(pickedAddon && it.kind == DesktopSourceKind.YOUTUBE)
}

internal suspend fun <T> walkDesktopSources(
    configs: List<DesktopSourceConfig>,
    availability: DesktopSourceAvailability,
    attempt: suspend (DesktopSourceConfig) -> T?,
): T? {
    for (config in configs.inSourceOrder()) {
        if (!availability.canAttempt(config)) continue
        try {
            attempt(config)?.let { return it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            availability.recordFailure(config, failure)
        }
    }
    return null
}
