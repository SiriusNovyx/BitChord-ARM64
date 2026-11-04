package com.music.bitchord.desktop

import kotlinx.serialization.Serializable
import java.net.URI

/** The kinds of source this build knows how to talk to. */
@Serializable
internal enum class DesktopSourceKind(
    val label: String,
    val detail: String,
    val labels: List<String>,
    val needsServer: Boolean,
    val supportsLossless: Boolean,
    val rank: Int,
) {
    /** An addon server the user pointed at themselves. */
    ADDON(
        label = "Addon",
        detail = "An addon server you host or were given a link to. Searched and streamed over " +
            "plain HTTP — no code is downloaded or run.",
        labels = listOf("FLAC", "Lossless", "Hi-Res"),
        needsServer = true,
        supportsLossless = true,
        rank = 0,
    ),

    /** A module index the user pointed at themselves. */
    CUSTOM_MODULE(
        label = DesktopStrings["d_custom_module", "Custom module"],
        detail = "Your own compatible module index. Tried before the built-in one.",
        labels = listOf("FLAC", "Lossless", "Hi-Res", "Plugins"),
        needsServer = true,
        supportsLossless = true,
        rank = 0,
    ),

    /** A module index seeded from a build-time URL. */
    MODULE(
        label = DesktopStrings["d_module_source", "Module source"],
        detail = "JavaScript modules for FLAC, lossless, Hi-Res and more.",
        labels = listOf("FLAC", "Lossless", "Hi-Res", "Plugins"),
        needsServer = true,
        supportsLossless = true,
        rank = 1,
    ),
    JIOSAAVN(
        label = "JioSaavn",
        detail = "High Quality · 320kbps",
        labels = listOf("High Quality", "320kbps"),
        needsServer = false,
        supportsLossless = false,
        rank = 2,
    ),
    SPOTIFY(
        label = "Spotify",
        detail = "Unavailable for ordered cross-provider playback. The existing native Spotify player remains separate.",
        labels = emptyList(),
        needsServer = false,
        supportsLossless = false,
        rank = 3,
    ),
    YOUTUBE(
        label = "YouTube Music",
        detail = "Lossy · Full catalogue · Radio",
        labels = listOf("Lossy", "Full catalogue", "Radio"),
        needsServer = false,
        supportsLossless = false,
        rank = 4,
    ),
}

/** The stream ceiling in force, as the rungs the settings sheet offers. */
internal enum class DesktopAudioQuality(
    /** Ceiling for the YouTube ladder. */
    val maxKbps: Int,
    val label: String,
    val detail: String,
) {
    LOW(64, "Low", "~64 kbps · smallest download"),
    MEDIUM(Int.MAX_VALUE, "Medium", "Best available · ~171 kbps Opus"),
    HIGH(Int.MAX_VALUE, "High", "JioSaavn up to 320kbps, YouTube fallback"),
    LOSSLESS(Int.MAX_VALUE, "Lossless", "Your addons + JioSaavn, bit-exact where available"),
    ;

    /** Whether a stream started under this ceiling may be served by [kind]. */
    fun permits(kind: DesktopSourceKind): Boolean = when (this) {
        LOSSLESS -> true
        // No lossless answer is wanted here, and a source that can serve one is the slow half of
        // the list.
        HIGH -> !kind.supportsLossless
        MEDIUM, LOW -> kind == DesktopSourceKind.YOUTUBE
    }

    companion object {
        /** The stored rung, migrating what older builds wrote. */
        fun stored(raw: String?, hasFourRungs: Boolean): DesktopAudioQuality {
            val value = raw?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return LOSSLESS
            val parsed = entries.firstOrNull { it.name == value }
                ?: if (value == "STANDARD") MEDIUM else null
                ?: return LOSSLESS
            return if (!hasFourRungs && parsed == HIGH) LOSSLESS else parsed
        }
    }
}

/** One source entry, including the user-visible name and enabled state. */
@Serializable
internal data class DesktopSourceConfig(
    val id: String,
    val kind: DesktopSourceKind,
    val label: String = "",
    val baseUrl: String = "",
    val enabled: Boolean = kind != DesktopSourceKind.JIOSAAVN && kind != DesktopSourceKind.SPOTIFY,
    /** The addon manifest's `allowDownloads`, as last read. */
    val allowDownloads: Boolean = true,
) {
    val displayName: String
        get() = label.ifBlank {
            baseUrl.takeIf(String::isNotBlank)
                ?.let { runCatching { URI.create(it).host }.getOrNull() }
                ?.takeIf(String::isNotBlank)
                ?: kind.label
        }

    val isComplete: Boolean
        get() = !kind.needsServer || baseUrl.isNotBlank()

    /** Whether this entry exists because the user added it, and so can be removed. */
    val isUserAdded: Boolean
        get() = kind == DesktopSourceKind.ADDON || kind == DesktopSourceKind.CUSTOM_MODULE || kind == DesktopSourceKind.MODULE
}

/** The persisted order within each group, with user-added sources before built-ins. */
internal fun List<DesktopSourceConfig>.inSourceOrder(): List<DesktopSourceConfig> =
    sortedBy { if (it.isUserAdded) 0 else 1 }

/** URL identity used to reject a second config for the same addon. */
internal fun canonicalSourceUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    return runCatching {
        val uri = URI.create(trimmed)
        val scheme = uri.scheme?.lowercase() ?: return@runCatching trimmed
        val host = uri.host?.lowercase() ?: return@runCatching trimmed
        val port = when {
            uri.port < 0 -> ""
            scheme == "http" && uri.port == 80 -> ""
            scheme == "https" && uri.port == 443 -> ""
            else -> ":${uri.port}"
        }
        val path = uri.rawPath.orEmpty().trimEnd('/').let { if (it == "/") "" else it }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val userInfo = uri.rawUserInfo?.let { "$it@" }.orEmpty()
        "$scheme://$userInfo$host$port$path$query"
    }.getOrDefault(trimmed)
}

internal fun List<DesktopSourceConfig>.duplicateOf(url: String, exceptId: String? = null): DesktopSourceConfig? {
    val wanted = canonicalSourceUrl(url)
    if (wanted.isBlank()) return null
    return firstOrNull { it.id != exceptId && canonicalSourceUrl(it.baseUrl) == wanted }
}

/** Moves a source inside its own custom or built-in priority group. */
internal fun moveUserSource(
    configs: List<DesktopSourceConfig>,
    sourceId: String,
    delta: Int,
): List<DesktopSourceConfig> {
    val source = configs.firstOrNull { it.id == sourceId } ?: return configs
    val userAdded = configs.inSourceOrder().filter { it.isUserAdded == source.isUserAdded }.toMutableList()
    val from = userAdded.indexOfFirst { it.id == sourceId }
    if (from < 0) return configs
    val to = (from + delta).coerceIn(0, userAdded.lastIndex)
    if (from == to) return configs
    val moved = userAdded.removeAt(from)
    userAdded.add(to, moved)
    val others = configs.filter { it.isUserAdded != source.isUserAdded }
    return (if (source.isUserAdded) userAdded + others else others + userAdded).inSourceOrder()
}
