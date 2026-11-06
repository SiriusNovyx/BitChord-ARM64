package com.music.bitchord.desktop

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal fun desktopPlaybackPresentation(
    playback: StateFlow<DesktopPlaybackState>,
    visualActive: StateFlow<Boolean>,
): Flow<DesktopPlaybackState> = combine(playback, visualActive) { state, active -> state to active }
    .distinctUntilChanged { before, after ->
        before.second == after.second && if (after.second) {
            before.first == after.first
        } else {
            before.first.copy(
                positionMs = after.first.positionMs,
                positionSampledAtNanos = after.first.positionSampledAtNanos,
            ) == after.first
        }
    }
    .map { it.first }
