package com.music.bitchord.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.music.bitchord.ui.LocalVisualActivity

@Composable
internal fun rememberBackgroundAlpha(target: Float): State<Float> {
    val active = LocalVisualActivity.current
    val alpha = remember { Animatable(target) }
    LaunchedEffect(target, active) {
        if (!active) {
            alpha.snapTo(target)
        } else if (alpha.value != target) {
            withBackgroundFramePacing {
                alpha.animateTo(target, tween(durationMillis = 360, easing = FastOutSlowInEasing))
            }
        }
    }
    return alpha.asState()
}

internal fun Modifier.backgroundAlphaLayer(value: () -> Float): Modifier = graphicsLayer {
    val next = value()
    if (alpha != next) DesktopVisualMetrics.collector.record(PerformanceStage.BACKGROUND_CROSSFADE_UPDATE)
    alpha = next
}
