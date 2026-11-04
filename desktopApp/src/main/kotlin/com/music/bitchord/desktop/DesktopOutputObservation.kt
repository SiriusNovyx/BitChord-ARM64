package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
internal fun ObserveDesktopOutput(
    engine: Any,
    playback: DesktopPlaybackState,
    snapshot: suspend () -> DesktopAudioPipeline,
    publish: (DesktopAudioPipeline) -> Unit,
) {
    LaunchedEffect(engine, playback.streamFormat, playback.isPlaying, playback.outputRevision) {
        publish(snapshot())
    }
}

internal fun activeDesktopOutputId(selected: String, available: List<String>): String =
    selected.takeIf { it in available } ?: DesktopAudioDevices.SYSTEM_DEFAULT
