package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopSourceDragTest {
    private val ids = listOf("a", "b", "c")
    private val heights = mapOf("a" to 80, "b" to 120, "c" to 160)

    @Test
    fun `drag waits until the next row center and handles unequal heights`() {
        assertEquals(0, sourceDragDelta(ids, heights, 0, 99f))
        assertEquals(1, sourceDragDelta(ids, heights, 0, 100f))
        assertEquals(2, sourceDragDelta(ids, heights, 0, 240f))
        assertEquals(-2, sourceDragDelta(ids, heights, 2, -240f))
    }

    @Test
    fun `drag stays inside the supplied priority group`() {
        assertEquals(2, sourceDragDelta(ids, heights, 0, 10_000f))
        assertEquals(-2, sourceDragDelta(ids, heights, 2, -10_000f))
        assertEquals(0, sourceDragDelta(listOf("a"), heights, 0, 10_000f))
        assertEquals(0, sourceDragDelta(ids, heights, -1, 10_000f))
    }
}
