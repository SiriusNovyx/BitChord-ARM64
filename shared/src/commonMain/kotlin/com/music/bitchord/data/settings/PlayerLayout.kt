package com.music.bitchord.data.settings

enum class PlayerLayout(val storageKey: String) {
    Modern("modern"),
    Classic("classic");

    companion object {
        fun fromStored(value: String?): PlayerLayout =
            entries.firstOrNull { it.storageKey == value } ?: Modern
    }
}
