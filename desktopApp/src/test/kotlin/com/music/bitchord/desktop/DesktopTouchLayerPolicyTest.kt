package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopTouchLayerPolicyTest {
    @Test fun `a focusable popup blocks a new touch from reaching the main scene`() {
        assertEquals(DesktopTouchLayerDecision(false, listOf(1)), desktopTouchLayerDecision(-1, listOf(false, true), true))
    }

    @Test fun `a nonfocusable popup gets its outside press before the main scene continues`() {
        assertEquals(DesktopTouchLayerDecision(true, listOf(1, 0)), desktopTouchLayerDecision(-1, listOf(false, false), true))
    }

    @Test fun `a popup owns only presses inside its content bounds`() {
        assertEquals(DesktopTouchLayerDecision(true, emptyList()), desktopTouchLayerDecision(1, listOf(true, true), true))
        assertEquals(DesktopTouchLayerDecision(false, listOf(1)), desktopTouchLayerDecision(1, listOf(true, true), false))
    }
}
