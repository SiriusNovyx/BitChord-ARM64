package com.music.bitchord.desktop

import com.music.bitchord.data.settings.PlayerLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DesktopPlayerLayoutSetting(private val persistence: DesktopPersistence = DesktopPersistence()) {
    private val selected = MutableStateFlow(PlayerLayout.fromStored(persistence.string(KEY, "")))
    val layout: StateFlow<PlayerLayout> = selected.asStateFlow()

    fun set(layout: PlayerLayout) {
        if (selected.value == layout) return
        persistence.saveString(KEY, layout.storageKey)
        selected.value = layout
    }

    private companion object { const val KEY = "player_layout" }
}
