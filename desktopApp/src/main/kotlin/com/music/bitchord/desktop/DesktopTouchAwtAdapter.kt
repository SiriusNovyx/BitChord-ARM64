package com.music.bitchord.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Native
import java.awt.Canvas
import java.awt.Component
import java.awt.Container
import java.awt.Window
import javax.swing.SwingUtilities

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
internal class DesktopTouchEndpoint(
    val handle: Long,
    val scene: ComposeScene,
    val component: Component,
    private val mediator: Any,
    private val composeContainer: Any,
    private val layer: Any?,
) {
    fun sceneOffset(): Offset {
        val container = DesktopTouchAwtAdapter.field(mediator, "container") as Component
        val offset = SwingUtilities.convertPoint(component, 0, 0, container)
        val bounds = mediator.javaClass.getMethod("getSceneBoundsInPx").invoke(mediator) as? Rect
        return Offset(offset.x * scene.density.density, offset.y * scene.density.density) -
            (bounds?.topLeft ?: Offset.Zero)
    }

    fun canBegin(x: Float, y: Float): Boolean {
        val layers = (DesktopTouchAwtAdapter.field(composeContainer, "layers") as List<*>).filterNotNull().toList()
        val owner = if (layer == null) -1 else layers.indexOfFirst { it === layer }
        if (layer != null && owner < 0) return false
        val windowContainer = composeContainer.javaClass.getMethod("getWindowContainer").invoke(composeContainer) as Component
        val origin = SwingUtilities.convertPoint(component, 0, 0, windowContainer)
        val px = origin.x * scene.density.density + x
        val py = origin.y * scene.density.density + y
        val bounds = layer?.javaClass?.getMethod("getBoundsInWindow")?.invoke(layer) as? IntRect
        val inside = bounds == null || (px >= bounds.left && px < bounds.right && py >= bounds.top && py < bounds.bottom)
        val decision = desktopTouchLayerDecision(owner,
            layers.map { it.javaClass.getMethod("getFocusable").invoke(it) as Boolean }, inside)
        for (index in decision.outsideLayers) {
            @Suppress("UNCHECKED_CAST")
            val callback = DesktopTouchAwtAdapter.field(layers[index], "outsidePointerCallback") as?
                ((PointerEventType, PointerButton?) -> Unit)
            callback?.invoke(PointerEventType.Press, PointerButton.Primary)
        }
        return decision.allow
    }
}

/** Kept at the pinned Compose 1.10.3 AWT boundary, which exposes no public touch dispatch API. */
@OptIn(InternalComposeUiApi::class)
internal object DesktopTouchAwtAdapter {
    fun discover(window: Window): List<DesktopTouchEndpoint> {
        if (window !is ComposeWindow && window !is ComposeDialog) return emptyList()
        val panel = field(window, "composePanel") ?: return emptyList()
        val container = field(panel, "_composeContainer") ?: return emptyList()
        val mediators = buildList<Pair<Any, Any?>> {
            add(checkNotNull(field(container, "mediator")) to null)
            for (layer in field(container, "layers") as List<*>) {
                if (layer != null) add(checkNotNull(field(layer, "mediator")) to layer)
            }
        }
        return mediators.mapNotNull { (mediator, layer) ->
            val type = mediator.javaClass
            val content = type.getMethod("getContentComponent").invoke(mediator) as Component
            val component = renderingCanvas(content) ?: return@mapNotNull null
            if (!component.isShowing) return@mapNotNull null
            // Skiko's windowHandle is the containing frame on Windows, not the canvas receiving touch.
            val handle = Native.getComponentID(component)
            if (handle == 0L) return@mapNotNull null
            val scene = type.getDeclaredMethod("getScene").apply { isAccessible = true }.invoke(mediator) as ComposeScene
            DesktopTouchEndpoint(handle, scene, component, mediator, container, layer).also { it.sceneOffset() }
        }
    }

    private fun renderingCanvas(content: Component): Canvas? {
        if (content is Canvas) return content
        return (content as? Container)?.components?.filterIsInstance<Canvas>()?.singleOrNull()
    }

    internal fun field(owner: Any, name: String): Any? {
        val type = generateSequence(owner.javaClass as Class<*>?) { it.superclass }
            .first { candidate -> candidate.declaredFields.any { it.name == name } }
        return type.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    }
}
