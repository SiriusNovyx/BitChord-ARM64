package com.music.bitchord.desktop

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.assertTrue

/** Windows-injected contact, not a physical digitizer acceptance test. */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class DesktopWindowsTouchInjectionTest {
    @Test fun windowsInjectedContactReachesProductionComposeScrolling() {
        org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless())
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val window = AtomicReference<ComposeWindow>()
        val scroll = AtomicReference<ScrollState>()
        SwingUtilities.invokeAndWait {
            window.set(ComposeWindow().apply {
                title = "BitChord OS touch-to-scroll probe"
                setContent {
                    val state = rememberScrollState()
                    SideEffect { scroll.set(state) }
                    Column(DesktopTouchTrace.observe(Modifier.fillMaxSize()).verticalScroll(state)) { repeat(100) { Text("Synthetic row $it") } }
                }
                setSize(460, 600)
                setLocation(100, 100)
                isAlwaysOnTop = true
                isVisible = true
                toFront()
            })
        }
        try {
            Thread.sleep(400)
            assertTrue(runBlocking { DesktopWindowsFrame.install(window.get().title) })
            Thread.sleep(300)
            val user32 = NativeLibrary.getInstance("user32")
            assertTrue(user32.getFunction("InitializeTouchInjection").invokeInt(arrayOf(1, 3)) != 0,
                "Windows must authorize this isolated injection test")
            val canvasHandle = AtomicReference<Pointer>()
            SwingUtilities.invokeAndWait {
                fun canvases(node: java.awt.Component): List<java.awt.Canvas> =
                    if (node is java.awt.Canvas) listOf(node)
                    else (node as? java.awt.Container)?.components?.flatMap(::canvases).orEmpty()
                canvasHandle.set(Pointer.createConstant(Native.getComponentID(canvases(window.get()).single())))
            }
            Memory(8).use { origin ->
                origin.clear()
                assertTrue(user32.getFunction("ClientToScreen").invokeInt(arrayOf(canvasHandle.get(), origin)) != 0)
                Memory(16).use { bounds ->
                    assertTrue(user32.getFunction("GetClientRect").invokeInt(arrayOf(canvasHandle.get(), bounds)) != 0)
                    val x = origin.getInt(0) + bounds.getInt(8) / 2
                    val start = origin.getInt(4) + bounds.getInt(12) * 3 / 4
                    val end = origin.getInt(4) + bounds.getInt(12) / 4
                    println("INJECTION_BOUNDS x=$x start=$start end=$end origin=${origin.getInt(0)},${origin.getInt(4)} size=${bounds.getInt(8)},${bounds.getInt(12)} desktop=" +
                        (76..79).joinToString { user32.getFunction("GetSystemMetrics").invokeInt(arrayOf(it)).toString() })
                    val hit = user32.getFunction("WindowFromPoint").invokePointer(arrayOf((start.toLong() shl 32) or (x.toLong() and 0xffffffffL)))
                    println("INJECTION_HIT=${Pointer.nativeValue(hit)} expected=${Pointer.nativeValue(canvasHandle.get())}")
                    assertTrue(Pointer.nativeValue(hit) == Pointer.nativeValue(canvasHandle.get()), "Contact must target the isolated fixture")
                    val before = scroll.get().value
                    SwingUtilities.invokeAndWait {
                        val endpoint = DesktopTouchAwtAdapter.discover(window.get()).single()
                        println("CONTACT_SCENE maxScroll=${scroll.get().maxValue} sceneSize=${endpoint.scene.size} " +
                            "offset=${endpoint.sceneOffset()} displayable=${window.get().isDisplayable}")
                    }
                    Memory(144).use { contact ->
                        fun inject(y: Int, flags: Int) {
                            contact.clear()
                            contact.setInt(0, 2) // PT_TOUCH; 64-bit POINTER_TOUCH_INFO follows the Windows ABI.
                            contact.setInt(4, 0)
                            contact.setInt(12, flags)
                            contact.setInt(32, x)
                            contact.setInt(36, y)
                            contact.setInt(100, 7)
                            contact.setInt(104, x - 2)
                            contact.setInt(108, y - 2)
                            contact.setInt(112, x + 2)
                            contact.setInt(116, y + 2)
                            contact.setInt(136, 90)
                            contact.setInt(140, 512)
                            val accepted = user32.getFunction("InjectTouchInput").invokeInt(arrayOf(1, contact))
                            assertTrue(accepted != 0, "Touch injection failed: " +
                                com.sun.jna.Native.getLastError())
                        }
                        inject(start, 0x10000 or 6)
                        for (step in 1..24) {
                            Thread.sleep(16)
                            inject(start + (end - start) * step / 24, 0x20000 or 6)
                        }
                        Thread.sleep(16)
                        inject(end, 0x40000)
                    }
                    Thread.sleep(300)
                    val after = scroll.get().value
                    println("WINDOWS_INJECTED_TOUCH_SCROLL before=$before after=$after inProgress=${scroll.get().isScrollInProgress} density=" +
                        window.get().graphicsConfiguration.defaultTransform.scaleX)
                    println(DesktopTouchTrace.snapshot())
                    assertTrue(after > before + 50, "OS-injected contact must scroll actual production Compose content")
                }
            }
        } finally { SwingUtilities.invokeAndWait { window.get().dispose() } }
    }
}
