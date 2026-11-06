package com.music.bitchord.desktop

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopTouchTraceTest {
    @Test fun traceIsOptInAndRetainsOnlyTheLatestBoundedNumericEvents() {
        repeat(5_000) { DesktopTouchTrace.record("buffer_test", id = it) }
        val snapshot = DesktopTouchTrace.snapshot()
        val events = snapshot.lineSequence().filter { it.split(',').getOrNull(1) == "buffer_test" && !it.startsWith("count,") }.toList()
        if (DesktopTouchTrace.enabled) {
            assertEquals(512, events.size)
            assertEquals("4488", events.first().split(',')[3])
            assertEquals("4999", events.last().split(',')[3])
            assertTrue(snapshot.contains("count,buffer_test,5000"))
            assertTrue(snapshot.length < 65_536, "Trace history cannot grow throughout a long session")
            assertEquals(1, Thread.getAllStackTraces().keys.count { it.name == "BitChord touch trace" && it.isDaemon })
        } else {
            assertTrue(events.isEmpty())
            assertTrue(Thread.getAllStackTraces().keys.none { it.name == "BitChord touch trace" })
        }
    }
}
