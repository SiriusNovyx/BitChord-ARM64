package com.music.bitchord.desktop

import androidx.compose.ui.InternalComposeUiApi
import java.awt.AWTEvent
import java.awt.Component
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ComponentEvent
import java.awt.event.WindowEvent
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/** Native callbacks only enqueue; Compose scene access stays on the AWT event thread. */
@OptIn(InternalComposeUiApi::class)
internal object DesktopTouchInput {
    private class Binding(val endpoint: DesktopTouchEndpoint) {
        val stream = DesktopTouchStream()
        val input = DesktopTouchScene(endpoint.scene)
        val blockedContacts = mutableSetOf<Int>()
        fun cancel() {
            blockedContacts.clear()
            stream.cancel(System.nanoTime() / 1_000_000)?.let(input::dispatch)
        }
    }

    private val bindings = ConcurrentHashMap<Long, Binding>()
    private var root: Window? = null
    private var refreshPending = false
    private var listener: AWTEventListener? = null
    private val rejected = mutableSetOf<Long>()

    fun install(title: String) {
        EventQueue.invokeLater {
            if (root != null) return@invokeLater
            root = Window.getWindows().firstOrNull { it is Frame && it.title == title && it.isShowing }
                ?: return@invokeLater
            val events = AWTEventListener { event ->
                val component = event.source as? Component ?: return@AWTEventListener
                val window = if (component is Window) component else SwingUtilities.getWindowAncestor(component)
                if (window == null || !belongsToRoot(window)) return@AWTEventListener
                if (event is java.awt.event.MouseEvent) {
                    DesktopTouchTrace.record("awt_mouse", id = event.id, x = event.x.toFloat(), y = event.y.toFloat(), detail = event.modifiersEx)
                    return@AWTEventListener
                }
                if (event.id in setOf(WindowEvent.WINDOW_DEACTIVATED, WindowEvent.WINDOW_LOST_FOCUS,
                        WindowEvent.WINDOW_CLOSED, ComponentEvent.COMPONENT_HIDDEN)) {
                    bindings.values.filter { SwingUtilities.getWindowAncestor(it.endpoint.component) == window }
                        .forEach(Binding::cancel)
                }
                scheduleRefresh()
            }
            listener = events
            Toolkit.getDefaultToolkit().addAWTEventListener(events,
                AWTEvent.WINDOW_EVENT_MASK or AWTEvent.WINDOW_FOCUS_EVENT_MASK or
                    AWTEvent.COMPONENT_EVENT_MASK or AWTEvent.CONTAINER_EVENT_MASK or
                    (if (DesktopTouchTrace.enabled) AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK else 0L))
            refresh()
        }
    }

    private fun belongsToRoot(window: Window): Boolean = window == root ||
        generateSequence(window.owner) { it.owner }.any { it == root }

    private fun scheduleRefresh() {
        if (refreshPending) return
        refreshPending = true
        EventQueue.invokeLater { refreshPending = false; refresh() }
    }

    private fun refresh() {
        val activeRoot = root ?: return
        if (!activeRoot.isDisplayable) {
            bindings.keys.toList().forEach(::detach)
            listener?.let { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
            listener = null
            root = null
            rejected.clear()
            return
        }
        val endpoints = Window.getWindows().filter { belongsToRoot(it) && it.isShowing }.flatMap { window ->
            runCatching { DesktopTouchAwtAdapter.discover(window) }.getOrElse {
                if (rejected.add(System.identityHashCode(window).toLong())) {
                    DesktopTrackLog.log("touch input adapter unavailable for pinned Compose: ${it.javaClass.simpleName}")
                }
                emptyList()
            }
        }
        val handles = endpoints.mapTo(mutableSetOf()) { it.handle }
        bindings.keys.filter { it !in handles }.forEach(::detach)
        for (endpoint in endpoints) {
            val existing = bindings[endpoint.handle]
            if (existing?.endpoint?.scene === endpoint.scene) continue
            if (existing != null) detach(endpoint.handle)
            if (endpoint.handle in rejected) continue
            val binding = Binding(endpoint)
            bindings[endpoint.handle] = binding
            DesktopTouchTrace.record("attach_request", endpoint.handle, detail = endpoint.scene.density.density.times(100).toInt())
            if (!runCatching { nativeSetTouchEnabled(endpoint.handle, true) }.getOrDefault(false)) {
                bindings.remove(endpoint.handle, binding)
                rejected.add(endpoint.handle)
                DesktopTrackLog.log("native touch input unavailable for Compose canvas")
                DesktopTouchTrace.record("attach_failed", endpoint.handle)
            } else {
                DesktopTouchTrace.record("attach_ok", endpoint.handle)
            }
        }
    }

    private fun detach(handle: Long) {
        val binding = bindings.remove(handle) ?: return
        binding.cancel()
        DesktopTouchTrace.record("detach", handle)
        runCatching { nativeSetTouchEnabled(handle, false) }
    }

    @JvmStatic
    private fun onNativeTouch(handle: Long, phase: Int, id: Int, x: Float, y: Float, millis: Long, dpi: Int) {
        DesktopTouchTrace.record("jni_contact", handle, id, x, y, phase)
        val expected = bindings[handle] ?: run {
            DesktopTouchTrace.record("unbound_contact", handle, id)
            return
        }
        EventQueue.invokeLater {
            if (bindings[handle] !== expected) return@invokeLater
            runCatching {
                if (phase == 3) {
                    DesktopTouchTrace.record("cancel", handle)
                    expected.cancel()
                    return@runCatching
                }
                if (phase == 0 && (expected.blockedContacts.isNotEmpty() || !expected.endpoint.canBegin(x, y))) {
                    expected.blockedContacts += id
                    DesktopTouchTrace.record("layer_blocked", handle, id, x, y)
                    return@runCatching
                }
                if (id in expected.blockedContacts) {
                    if (phase == 2) expected.blockedContacts -= id
                    return@runCatching
                }
                val offset = expected.endpoint.sceneOffset()
                expected.stream.accept(phase, id, x + offset.x, y + offset.y, millis, dpi)
                    ?.let {
                        DesktopTouchTrace.record("scene_dispatch", handle, id, x + offset.x, y + offset.y, phase)
                        expected.input.dispatch(it)
                    }
            }.onFailure {
                // Return touch to Windows when this pinned adapter can no longer service the canvas.
                detach(handle)
                rejected.add(handle)
                DesktopTrackLog.log("touch input detached after Compose scene changed: ${it.javaClass.simpleName}")
                DesktopTouchTrace.record("scene_failure", handle)
            }
        }
    }

    @JvmStatic
    private fun onNativeTrace(handle: Long, message: Int, detail: Int) {
        DesktopTouchTrace.record("native_message", handle, id = message, detail = detail)
    }

    @JvmStatic
    private external fun nativeSetTouchEnabled(handle: Long, enabled: Boolean): Boolean
}
