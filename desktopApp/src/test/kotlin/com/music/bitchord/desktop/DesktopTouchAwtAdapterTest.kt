package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.dp
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.awt.Canvas
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class DesktopTouchAwtAdapterTest {
    @Test fun `displayed touch endpoint belongs to rendering canvas rather than its frame`() {
        withDisplayedWindow { window ->
            SwingUtilities.invokeAndWait {
                val canvas = canvases(window).single()
                val endpoint = DesktopTouchAwtAdapter.discover(window).single()
                assertEquals(Native.getComponentID(canvas), endpoint.handle)
                assertTrue(endpoint.component === canvas)
                assertTrue(endpoint.handle != Native.getComponentID(window))
            }
        }
    }

    @Test fun `detaching native touch preserves the registration already owned by AWT`() {
        assumeTrue(DesktopPlatform.isWindows)
        withDisplayedWindow { window ->
            val canvas = AtomicReference<Canvas>()
            var registeredBefore = false
            var flagsBefore = 0
            SwingUtilities.invokeAndWait {
                canvas.set(canvases(window).single())
                Memory(4).use { flags ->
                    registeredBefore = isTouchWindow(Native.getComponentID(canvas.get()), flags)
                    flagsBefore = flags.getInt(0)
                }
            }
            assertTrue(runBlocking { DesktopWindowsFrame.install(window.title) })
            SwingUtilities.invokeAndWait {
                val handle = Native.getComponentID(canvas.get())
                val configure = DesktopTouchInput::class.java.getDeclaredMethod("nativeSetTouchEnabled",
                    java.lang.Long.TYPE, java.lang.Boolean.TYPE).apply { isAccessible = true }
                assertEquals(true, configure.invoke(null, handle, true))
                assertEquals(true, configure.invoke(null, handle, false))
                Memory(4).use { flags ->
                    assertEquals(registeredBefore, isTouchWindow(handle, flags))
                    if (registeredBefore) assertEquals(flagsBefore, flags.getInt(0))
                }
            }
        }
    }

    private fun isTouchWindow(handle: Long, flags: Memory): Boolean =
        NativeLibrary.getInstance("user32").getFunction("IsTouchWindow")
            .invokeInt(arrayOf(Pointer.createConstant(handle), flags)) != 0

    private fun canvases(component: Component): List<Canvas> = when (component) {
        is Canvas -> listOf(component)
        is Container -> component.components.flatMap(::canvases)
        else -> emptyList()
    }

    private fun withDisplayedWindow(block: (ComposeWindow) -> Unit) {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val holder = AtomicReference<ComposeWindow>()
        SwingUtilities.invokeAndWait {
            holder.set(ComposeWindow().apply {
                title = "BitChord native touch regression"
                setContent { Box(Modifier.size(200.dp)) }
                setSize(400, 300)
                isVisible = true
            })
        }
        try { block(holder.get()) } finally { SwingUtilities.invokeAndWait { holder.get().dispose() } }
    }

    @Test fun `the real pinned AWT window exposes the guarded mediator and coordinate path`() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        SwingUtilities.invokeAndWait {
            val window = ComposeWindow()
            try {
                window.setContent { Box(Modifier.size(100.dp)) }
                val panel = DesktopTouchAwtAdapter.field(window, "composePanel")!!
                val container = DesktopTouchAwtAdapter.field(panel, "_composeContainer")!!
                val mediator = DesktopTouchAwtAdapter.field(container, "mediator")!!
                val scene = mediator.javaClass.getDeclaredMethod("getScene").apply { isAccessible = true }.invoke(mediator) as ComposeScene
                val component = mediator.javaClass.getMethod("getContentComponent").invoke(mediator) as Component
                val endpoint = DesktopTouchEndpoint(0, scene, component, mediator, container, null)
                assertEquals(Offset.Zero, endpoint.sceneOffset())
                assertTrue(endpoint.canBegin(10f, 10f))
                assertTrue(DesktopTouchAwtAdapter.discover(window).isEmpty(), "a hidden canvas must not take over native touch")
            } finally { window.dispose() }
        }
    }
}
