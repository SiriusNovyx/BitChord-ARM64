package com.music.bitchord.desktop

import com.music.bitchord.ui.player.BackgroundFrameRate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DesktopBackgroundRateSetting(private val read: () -> String?, private val write: (String) -> Unit) {
    private val selected = MutableStateFlow(BackgroundFrameRate.stored(read()))
    val rate = selected.asStateFlow()

    fun set(value: BackgroundFrameRate) {
        if (selected.value == value) return
        write(value.storageKey)
        selected.value = value
    }
}
