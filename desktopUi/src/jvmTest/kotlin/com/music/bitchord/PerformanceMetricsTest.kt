package com.music.bitchord

import com.music.bitchord.ui.player.PerformanceMetrics
import com.music.bitchord.ui.player.PerformanceStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerformanceMetricsTest {
    @Test fun `work started before reset cannot contaminate the new recording`() {
        var now = 0L
        val metrics = PerformanceMetrics(nowNanos = { now })
        metrics.setEnabled(true)
        val old = metrics.begin()
        now = 10
        metrics.reset()
        now = 20
        metrics.finish(PerformanceStage.TRACK_ANALYSIS, old)
        assertTrue(metrics.snapshot().isEmpty(), "An obsolete analysis must not appear in the reset Startup bucket")
        val current = metrics.begin()
        now = 25
        metrics.finish(PerformanceStage.TRACK_ANALYSIS, current)
        assertEquals(5L, metrics.snapshot().single().meanNanos)
    }

    @Test fun `disabling and reenabling rejects measurements owned by the old session`() {
        var now = 0L
        val metrics = PerformanceMetrics(nowNanos = { now })
        metrics.setEnabled(true)
        val old = metrics.begin()
        metrics.setEnabled(false)
        metrics.setEnabled(true)
        now = 10
        metrics.finish(PerformanceStage.IMAGE_PREPARATION, old)
        assertTrue(metrics.snapshot().isEmpty())
    }

    @Test fun `disabled collector does not call clock or retain samples`() {
        val metrics = PerformanceMetrics(nowNanos = { error("disabled clock read") })
        repeat(1000) { metrics.record(PerformanceStage.LYRICS_COMPOSE) }
        assertTrue(metrics.snapshot().isEmpty())
    }

    @Test fun `startup and warmed samples are separated with bounded retention`() {
        var now = 0L
        val metrics = PerformanceMetrics(capacity = 4, warmAfterNanos = 100, nowNanos = { now })
        metrics.setEnabled(true)
        metrics.record(PerformanceStage.IMAGE_PREPARATION, 7)
        now = 100
        repeat(20) { metrics.record(PerformanceStage.IMAGE_PREPARATION, it.toLong()) }
        val samples = metrics.snapshot()
        assertEquals(2, samples.size)
        assertEquals(1L, samples.first().count)
        assertEquals("Startup", samples.first().phase)
        assertEquals("Warmed", samples.last().phase)
        assertEquals(20L, samples.last().count)
        assertEquals(4, samples.last().retained)
        assertEquals(19L, samples.last().maxNanos)
        metrics.reset()
        assertTrue(metrics.snapshot().isEmpty())
    }

    @Test fun `frame gaps are observed once per timestamp and reset across visibility gaps`() {
        var now = 0L
        val metrics = PerformanceMetrics(nowNanos = { now })
        metrics.setEnabled(true)
        metrics.backgroundFrame(10)
        metrics.backgroundFrame(10)
        now = 1
        metrics.backgroundFrame(30)
        assertEquals(20L, metrics.snapshot().single().maxNanos)
        metrics.pauseFrames()
        metrics.backgroundFrame(1000)
        assertEquals(1L, metrics.snapshot().single().count)
        metrics.setEnabled(false)
        assertTrue(metrics.snapshot().isEmpty())
    }
}
