package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopTouchStreamTest {
    @Test fun `a contact keeps its identity outside the window until release`() {
        val stream = DesktopTouchStream()
        val down = stream.accept(0, 7, 20f, 30f, 100, 144)!!
        assertEquals(0, down.phase)
        assertTrue(down.pointers.single().pressed)
        val move = stream.accept(1, 7, -30f, 900f, 116, 144)!!
        assertEquals(-30f, move.pointers.single().x)
        assertEquals(7L, move.pointers.single().id)
        assertEquals(2, stream.accept(2, 7, -30f, 900f, 120, 144)!!.phase)
        assertNull(stream.accept(1, 7, 20f, 30f, 130, 144))
    }

    @Test fun `multiple contacts retain the others on release`() {
        val stream = DesktopTouchStream()
        stream.accept(0, 7, 20f, 30f, 100, 96)
        assertEquals(2, stream.accept(0, 9, 80f, 30f, 101, 96)!!.pointers.size)
        val up = stream.accept(2, 7, 20f, 30f, 110, 96)!!
        assertEquals(listOf(false, true), up.pointers.map { it.pressed })
        assertEquals(listOf(9L), stream.accept(1, 9, 90f, 30f, 115, 96)!!.pointers.map { it.id })
    }

    @Test fun `capture loss cancels without a synthetic release or fling`() {
        val stream = DesktopTouchStream()
        stream.accept(0, 1, 20f, 30f, 100, 96)
        assertEquals(3, stream.accept(3, 0, 0f, 0f, 110, 96)!!.phase)
        assertNull(stream.accept(2, 1, 20f, 30f, 120, 96))
        assertNull(stream.accept(1, 1, 20f, 30f, 130, 96))
        assertEquals(0, stream.accept(0, 1, 20f, 30f, 140, 96)!!.phase)
    }

    @Test fun `DPI change or reversed time cancels the current gesture`() {
        for (dpi in listOf(144, 96)) {
            val stream = DesktopTouchStream()
            stream.accept(0, 1, 20f, 30f, 100, 96)
            val time = if (dpi == 96) 99L else 110L
            assertEquals(3, stream.accept(1, 1, 40f, 50f, time, dpi)!!.phase)
            assertNull(stream.accept(2, 1, 40f, 50f, 120, dpi))
        }
    }

    @Test fun `invalid packets and unknown moves cannot begin a gesture`() {
        val stream = DesktopTouchStream()
        assertNull(stream.accept(1, 1, 2f, 3f, 100, 96))
        assertNull(stream.accept(2, 1, 2f, 3f, 100, 96))
        assertNull(stream.accept(0, 1, Float.NaN, 3f, 100, 96))
        assertNull(stream.accept(0, 1, 2f, 3f, 100, 0))
    }
}
