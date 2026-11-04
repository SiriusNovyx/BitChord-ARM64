package com.music.bitchord.desktop

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DesktopTouchSceneTest {
    private fun fixture(horizontal: Boolean, density: Float = 1f, child: Modifier = Modifier, action: (ImageComposeScene, DesktopTouchScene, ScrollState) -> Unit) {
        val scroll = ScrollState(0)
        val image = ImageComposeScene(300, 300, Density(density), coroutineContext = Dispatchers.Unconfined) {
            Box(Modifier.size(150.dp).then(if (horizontal) Modifier.horizontalScroll(scroll) else Modifier.verticalScroll(scroll))) {
                Box(Modifier.size(1200.dp).then(child).background(Color.Red))
            }
        }
        try {
            image.render(0).close()
            val scene = ImageComposeScene::class.java.getDeclaredField("scene").apply { isAccessible = true }.get(image) as ComposeScene
            action(image, DesktopTouchScene(scene), scroll)
        } finally { image.close() }
    }

    @Test fun `real touch scrolls both axes and mouse drag does not scroll`() {
        for (horizontal in listOf(false, true)) fixture(horizontal) { image, bridge, scroll ->
            val stream = DesktopTouchStream()
            bridge.dispatch(stream.accept(0, 8, 120f, 120f, 100, 96)!!)
            bridge.dispatch(stream.accept(1, 8, if (horizontal) 118f else 120f, if (horizontal) 120f else 118f, 110, 96)!!)
            assertEquals(0, scroll.value, "motion below slop must not scroll")
            bridge.dispatch(stream.accept(1, 8, if (horizontal) 40f else 120f, if (horizontal) 120f else 40f, 130, 96)!!)
            image.render(140_000_000).close()
            assertTrue(scroll.value > 30, "Touch must scroll the ${if (horizontal) "horizontal" else "vertical"} surface")
            bridge.dispatch(stream.accept(3, 0, 0f, 0f, 140, 96)!!)
            val stopped = scroll.value
            image.render(700_000_000).close()
            assertEquals(stopped, scroll.value, "cancellation must not launch a fling")
            image.sendPointerEvent(PointerEventType.Press, Offset(120f, 120f), timeMillis = 800,
                type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true))
            image.sendPointerEvent(PointerEventType.Move, Offset(40f, 40f), timeMillis = 840,
                type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true))
            image.sendPointerEvent(PointerEventType.Release, Offset(40f, 40f), timeMillis = 850,
                type = PointerType.Mouse, buttons = PointerButtons())
            image.render(900_000_000).close()
            assertEquals(stopped, scroll.value, "ordinary mouse drags must stay available for selection and reorder")
        }
    }

    @Test fun `release flings and the next touch stops momentum`() {
        fixture(false) { image, bridge, scroll ->
            val stream = DesktopTouchStream()
            bridge.dispatch(stream.accept(0, 1, 100f, 130f, 100, 96)!!)
            for (step in 1..6) bridge.dispatch(stream.accept(1, 1, 100f, 130f - step * 15f, 100L + step * 10, 96)!!)
            bridge.dispatch(stream.accept(2, 1, 100f, 35f, 170, 96)!!)
            val released = scroll.value
            for (time in 180L..300L step 20) image.render(time * 1_000_000).close()
            assertTrue(scroll.value > released, "a release with measured velocity must fling")
            bridge.dispatch(stream.accept(0, 2, 100f, 100f, 310, 96)!!)
            image.render(320_000_000).close()
            val held = scroll.value
            image.render(700_000_000).close()
            assertEquals(held, scroll.value, "touch down must stop the running fling")
            bridge.cancel()
        }
    }

    @Test fun `a nested drag control owns its touch instead of scrolling its parent`() {
        var dragged = 0f
        val control = Modifier.pointerInput(Unit) {
            detectDragGestures { change, delta -> change.consume(); dragged += delta.y }
        }
        fixture(false, child = control) { image, bridge, scroll ->
            val stream = DesktopTouchStream()
            bridge.dispatch(stream.accept(0, 1, 100f, 120f, 100, 96)!!)
            bridge.dispatch(stream.accept(1, 1, 100f, 40f, 140, 96)!!)
            image.render(150_000_000).close()
            assertTrue(dragged < -30f)
            assertEquals(0, scroll.value)
            bridge.cancel()
        }
    }

    @Test fun `a tap clicks once and a scroll does not click the nested item`() {
        var clicks = 0
        fixture(false, child = Modifier.clickable { clicks++ }) { image, bridge, _ ->
            val stream = DesktopTouchStream()
            bridge.dispatch(stream.accept(0, 1, 100f, 120f, 100, 96)!!)
            bridge.dispatch(stream.accept(2, 1, 100f, 120f, 120, 96)!!)
            assertEquals(1, clicks)
            bridge.dispatch(stream.accept(0, 2, 100f, 120f, 200, 96)!!)
            bridge.dispatch(stream.accept(1, 2, 100f, 40f, 240, 96)!!)
            bridge.dispatch(stream.accept(2, 2, 100f, 40f, 250, 96)!!)
            image.render(260_000_000).close()
            assertEquals(1, clicks)
        }
    }

    @Test fun `mouse text selection inside a scroll surface stays available`() {
        val value = mutableStateOf(TextFieldValue("Choose a portion of this text with the mouse"))
        val scroll = ScrollState(0)
        val image = ImageComposeScene(300, 300, Density(1f), coroutineContext = Dispatchers.Unconfined) {
            Box(Modifier.size(250.dp).verticalScroll(scroll)) {
                BasicTextField(value.value, { value.value = it }, modifier = Modifier.size(250.dp, 1000.dp))
            }
        }
        try {
            image.render(0).close()
            image.sendPointerEvent(PointerEventType.Press, Offset(5f, 8f), timeMillis = 100,
                type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            image.sendPointerEvent(PointerEventType.Move, Offset(130f, 8f), timeMillis = 150,
                type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true))
            image.sendPointerEvent(PointerEventType.Release, Offset(130f, 8f), timeMillis = 160,
                type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
            image.render(170_000_000).close()
            assertTrue(!value.value.selection.collapsed, "text selection must receive the real mouse drag")
            assertEquals(0, scroll.value)
        } finally { image.close() }
    }

    @Test fun `native scene positions stay physical pixels at double density`() {
        fixture(false, 2f) { image, bridge, scroll ->
            val stream = DesktopTouchStream()
            bridge.dispatch(stream.accept(0, 1, 100f, 200f, 100, 192)!!)
            bridge.dispatch(stream.accept(1, 1, 100f, 100f, 140, 192)!!)
            image.render(160_000_000).close()
            assertTrue(scroll.value in 50..100, "DPI must not double-scale the native delta: ${scroll.value}")
            bridge.cancel()
        }
    }

    @Test fun `the pinned AWT scene path exists before native touch can be enabled`() {
        val panel = Class.forName("androidx.compose.ui.awt.ComposeWindowPanel")
        val container = panel.getDeclaredField("_composeContainer").type
        val mediator = container.getDeclaredField("mediator").type
        assertTrue(ComposeScene::class.java.isAssignableFrom(mediator.getDeclaredMethod("getScene").returnType))
        assertTrue(container.getDeclaredMethod("getContentComponent").returnType.name.startsWith("javax.swing."))
    }
}
