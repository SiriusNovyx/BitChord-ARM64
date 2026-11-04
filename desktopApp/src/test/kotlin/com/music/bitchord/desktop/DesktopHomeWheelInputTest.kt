package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.PullToRefresh
import org.junit.Test
import java.awt.Canvas
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseWheelEvent
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.assertEquals

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
class DesktopHomeWheelInputTest {
    @Test fun ordinaryWheelBurstAtTheTopDoesNotRefresh() {
        org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless())
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val window = AtomicReference<ComposeWindow>()
        val calls = AtomicInteger()
        val list = LazyListState()
        SwingUtilities.invokeAndWait {
            window.set(ComposeWindow().apply {
                title = "BitChord actual AWT Home wheel probe"
                setContent {
                    MaterialTheme {
                        Box(Modifier.onPointerEvent(PointerEventType.Scroll) { event ->
                            println("AWT_TO_COMPOSE_WHEEL delta=" + event.changes.first().scrollDelta)
                        }) {
                            DesktopHomeRefreshPane(list, false, HomeRefreshResult.IDLE, PaddingValues(), { calls.incrementAndGet() }) { state, refresh ->
                                PullToRefresh(false, refresh, state) {
                                    LazyColumn(Modifier.fillMaxSize(), state = list) { items(20) { Box(Modifier.height(60.dp)) } }
                                }
                            }
                        }
                    }
                }
                setSize(460, 600)
                isVisible = true
            })
        }
        try {
            Thread.sleep(400)
            fun canvas(component: Component): Canvas? = component as? Canvas ?: (component as? Container)?.components?.firstNotNullOfOrNull(::canvas)
            fun notch(rotation: Int) {
                SwingUtilities.invokeAndWait {
                    val target = checkNotNull(canvas(window.get()))
                    target.dispatchEvent(MouseWheelEvent(target, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(),
                        0, 120, 120, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation))
                }
            }
            repeat(3) {
                notch(-1)
                Thread.sleep(20)
            }
            Thread.sleep(500)
            println("ORDINARY_AWT_WHEEL_REFRESHES=" + calls.get())
            assertEquals(0, calls.get(), "An ordinary burst must not arm and refresh merely because the page starts at its top")
            repeat(4) { notch(-1); Thread.sleep(110) }
            assertEquals(0, calls.get(), "Deliberate wheel arming still waits for idle")
            Thread.sleep(350)
            println("DELIBERATE_AWT_WHEEL_REFRESHES=" + calls.get())
            assertEquals(1, calls.get(), "A prepared deliberate overscroll must actually refresh")
            repeat(4) { notch(-1); Thread.sleep(100) }
            Thread.sleep(350)
            assertEquals(1, calls.get(), "Repeated input during cooldown cannot refresh twice")
        } finally { SwingUtilities.invokeAndWait { window.get().dispose() } }
    }
}
