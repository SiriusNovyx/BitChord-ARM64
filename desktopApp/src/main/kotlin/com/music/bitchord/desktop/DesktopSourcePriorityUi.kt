package com.music.bitchord.desktop

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp

/** Handles own the drag; the existing source row keeps its switches and keyboard move buttons. */
@Composable
internal fun DesktopSourcePriorityList(
    configs: List<DesktopSourceConfig>,
    onMove: (DesktopSourceConfig, Int) -> Unit,
    reorderEnabled: Boolean = true,
    row: @Composable (Int, DesktopSourceConfig) -> Unit,
) {
    val ordered = configs.inSourceOrder()
    val heights = remember { mutableStateMapOf<String, Int>() }
    Column(Modifier.fillMaxWidth()) {
        listOf(true, false).forEach { custom ->
            val group = ordered.filter { it.isUserAdded == custom }
            if (group.isNotEmpty()) {
                Text(if (custom) "Your sources" else "Built-in sources", style = MaterialTheme.typography.labelLarge)
            }
            group.forEach { config ->
                key(config.id) {
                    var drag by remember { mutableStateOf(0f) }
                    Row(Modifier.fillMaxWidth().onSizeChanged { heights[config.id] = it.height }.graphicsLayer { translationY = drag }, verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(48.dp).pointerInput(group.map { it.id }, reorderEnabled) {
                                if (!reorderEnabled || group.size < 2) return@pointerInput
                                detectDragGestures(
                                    onDragStart = { drag = 0f },
                                    onDragCancel = { drag = 0f },
                                    onDragEnd = {
                                        val from = group.indexOfFirst { it.id == config.id }
                                        val delta = sourceDragDelta(group.map { it.id }, heights, from, drag)
                                        drag = 0f
                                        if (delta != 0) onMove(config, delta)
                                    },
                                ) { change, amount ->
                                    change.consume()
                                    drag += amount.y
                                }
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.DragHandle, "Drag ${config.displayName} within ${if (custom) "your sources" else "built-in sources"}")
                        }
                        Box(Modifier.weight(1f)) { row(ordered.indexOf(config) + 1, config) }
                    }
                }
            }
        }
    }
}

internal fun sourceDragDelta(ids: List<String>, heights: Map<String, Int>, from: Int, drag: Float): Int {
    if (from !in ids.indices) return 0
    var remaining = kotlin.math.abs(drag)
    var to = from
    val step = if (drag < 0) -1 else 1
    while (to + step in ids.indices) {
        val next = to + step
        val threshold = ((heights[ids[to]] ?: 48) + (heights[ids[next]] ?: 48)) / 2f
        if (remaining < threshold) break
        remaining -= threshold
        to = next
    }
    return to - from
}
