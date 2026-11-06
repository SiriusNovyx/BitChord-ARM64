package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DesktopOutputPillSetting(private val persistence: DesktopPersistence = DesktopPersistence()) {
    private val selected = MutableStateFlow(persistence.boolean(KEY, true))
    val shown: StateFlow<Boolean> = selected.asStateFlow()

    fun set(shown: Boolean) {
        persistence.saveBoolean(KEY, shown)
        selected.value = shown
    }

    private companion object { const val KEY = "show_listen_together_button" }
}
