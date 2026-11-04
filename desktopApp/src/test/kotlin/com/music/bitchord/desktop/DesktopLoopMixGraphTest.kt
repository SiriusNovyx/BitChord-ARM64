package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.LoopMix
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class DesktopLoopMixGraphTest {
    private val rate = 48_000
    private fun plan(): LoopMix.Plan {
        fun pick(start: Double) = LoopMix.Pick(start, start + 8, 4, 0.5, 120.0, 0.0, LoopMix.Check.MASK)
        return LoopMix.Plan(pick(90.0), pick(2.0), 1.0, 4, 2, 2, 8)
    }
    private fun region(from: Double, to: Double): DesktopLoopMixGraph.Region {
        val count = kotlin.math.ceil((to - from) * rate).toInt()
        val left = FloatArray(count) { (0.1 * sin(2 * PI * 110 * (from + it.toDouble() / rate))).toFloat() }
        return DesktopLoopMixGraph.Region(left, FloatArray(count) { left[it] * 0.5f }, from)
    }

    @Test fun cancelledPreparationNeverDecodesAnotherRegionOrPublishesGraph() {
        var calls = 0
        var cancelled = false
        val graph = DesktopLoopMixGraph.build(plan(), rate, 1f, { cancelled }) { _, from, to ->
            calls++
            cancelled = true
            region(from, to)
        }
        assertNull(graph)
        assertEquals(1, calls)
    }

    @Test fun missingOrTruncatedPcmDoesNotCreateAnApparentlyReadyGraph() {
        assertNull(DesktopLoopMixGraph.build(plan(), rate, 1f) { _, _, _ -> null })
        assertNull(DesktopLoopMixGraph.build(plan(), rate, 1f) { _, from, to -> region(from, to - 1) })
        assertNull(DesktopLoopMixGraph.build(plan(), rate, 1f) { _, from, to -> region(from + 0.1, to) })
    }

    @Test fun realGraphFollowsOneClockThroughLoopReleaseAndHandover() {
        val graph = DesktopLoopMixGraph.build(plan(), rate, 0.75f) { _, from, to -> region(from, to) }
        assertNotNull(graph)
        graph!!.use {
            assertEquals(-1200L, graph.nextFrame)
            assertEquals(89_975_000L, graph.takeoverUs)
            val buffer = FloatArray(1024)
            var active = 0
            var peak = 0f
            while (graph.nextFrame < graph.handoverFrame) {
                val n = minOf(512L, graph.handoverFrame - graph.nextFrame).toInt()
                graph.render(buffer, 0, n)
                for (i in 0 until n) {
                    val left = buffer[2 * i]
                    assertTrue(left.isFinite())
                    assertEquals(left * 0.5f, buffer[2 * i + 1], 1e-4f)
                    peak = maxOf(peak, abs(left))
                    if (abs(left) > 1e-5) active++
                }
                val time = graph.nextFrame.toDouble() / rate
                val incoming = LoopMix.positionAt(graph.clock, LoopMix.Side.IN, time)
                if (incoming != null) assertEquals(incoming, graph.heard(LoopMix.Side.IN), 1e-5)
            }
            assertTrue(active > rate * 5)
            assertTrue(peak in 0.01f..0.5f)
            assertEquals(graph.clock.elementTrackTime, graph.heard(LoopMix.Side.IN), 1e-4)
        }
    }
}
