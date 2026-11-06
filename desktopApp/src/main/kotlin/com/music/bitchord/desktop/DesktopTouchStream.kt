package com.music.bitchord.desktop

internal data class DesktopTouchPoint(val id: Long, val x: Float, val y: Float, val pressed: Boolean)
internal data class DesktopTouchFrame(val phase: Int, val pointers: List<DesktopTouchPoint>, val timeMillis: Long)

internal class DesktopTouchStream {
    private val contacts = linkedMapOf<Int, DesktopTouchPoint>()
    private var lastTime = 0L
    private var gestureDpi = 0

    fun accept(phase: Int, id: Int, x: Float, y: Float, timeMillis: Long, dpi: Int): DesktopTouchFrame? {
        if (phase == 3) return cancel(timeMillis)
        if (phase !in 0..2 || !x.isFinite() || !y.isFinite() || dpi <= 0) return cancel(timeMillis)
        if (contacts.isNotEmpty() && (dpi != gestureDpi || timeMillis < lastTime)) return cancel(timeMillis)
        if (phase != 0 && id !in contacts) return null
        if (phase == 0 && id in contacts) return cancel(timeMillis)
        if (contacts.isEmpty()) gestureDpi = dpi
        lastTime = timeMillis
        contacts[id] = DesktopTouchPoint(id.toLong() and 0xffffffffL, x, y, phase != 2)
        val frame = DesktopTouchFrame(phase, contacts.values.toList(), timeMillis)
        if (phase == 2) contacts.remove(id)
        return frame
    }

    fun cancel(timeMillis: Long): DesktopTouchFrame? {
        if (contacts.isEmpty()) return null
        contacts.clear()
        return DesktopTouchFrame(3, emptyList(), maxOf(timeMillis, lastTime))
    }
}
