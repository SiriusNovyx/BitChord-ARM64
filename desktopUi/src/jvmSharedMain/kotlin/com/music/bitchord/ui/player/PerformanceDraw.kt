package com.music.bitchord.ui.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent

internal fun Modifier.observeBackgroundDraw(): Modifier = drawWithContent {
    val start = DesktopVisualMetrics.collector.begin()
    try { drawContent() }
    finally { DesktopVisualMetrics.collector.finish(PerformanceStage.BACKGROUND_DRAW, start) }
}
