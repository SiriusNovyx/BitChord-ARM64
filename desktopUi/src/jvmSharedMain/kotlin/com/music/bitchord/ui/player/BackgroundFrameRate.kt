package com.music.bitchord.ui.player

enum class BackgroundFrameRate(val storageKey: String, val label: String, val fps: Int?) {
    DYNAMIC("dynamic", "Dynamic", null),
    FPS_15("15", "15 FPS", 15),
    FPS_30("30", "30 FPS", 30),
    FPS_60("60", "60 FPS", 60);

    companion object {
        fun stored(raw: String?): BackgroundFrameRate = entries.firstOrNull { it.storageKey == raw } ?: DYNAMIC
    }
}

fun backgroundFramesPerSecond(rate: BackgroundFrameRate, visible: Boolean, playing: Boolean, onBattery: Boolean): Int = when {
    !visible -> 0
    rate.fps != null -> rate.fps
    !playing -> 15
    onBattery -> 30
    else -> 60
}
