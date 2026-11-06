package com.music.bitchord.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerLayoutTest {
    @Test fun missingOrUnknownPreferenceUsesModern() {
        for (stored in listOf(null, "", "legacy-unknown", "0", "CLASSIC")) {
            assertEquals(PlayerLayout.Modern, PlayerLayout.fromStored(stored))
        }
    }

    @Test fun stableKeysRoundTripIndependentlyOfEnumOrder() {
        assertEquals("modern", PlayerLayout.Modern.storageKey)
        assertEquals("classic", PlayerLayout.Classic.storageKey)
        for (layout in PlayerLayout.entries) {
            assertEquals(layout, PlayerLayout.fromStored(layout.storageKey))
        }
    }
}
