package com.music.bitchord.desktop

import androidx.compose.ui.awt.ComposeWindow
import com.music.bitchord.ui.player.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.assertTrue

class DesktopBackgroundRenderingTest {
    @Test fun activeRendererDrawsAtTheCurrentlySelectedCap() {
        org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless())
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val previousMode = DesktopPerformance.mode.value
        val previousRate = DesktopPerformance.backgroundRate.value
        PlayerPlatform.install(DesktopPlayerHost)
        DesktopPerformance.setMode(DesktopPerformanceMode.PERFORMANCE)
        DesktopPerformance.setPlaying(true)
        DesktopVisualMetrics.collector.setEnabled(true)
        val window = AtomicReference<ComposeWindow>()
        SwingUtilities.invokeAndWait {
            window.set(ComposeWindow().apply {
                title = "BitChord real background draw cadence probe"
                setContent { MeshGradientBackground(MeshPalette(emptyList()), trackKey = "fixture", continuous = true) }
                setSize(240, 240)
                setLocation(80, 80)
                isAlwaysOnTop = true
                isVisible = true
            })
        }
        try {
            Thread.sleep(600)
            for (rate in listOf(BackgroundFrameRate.FPS_15, BackgroundFrameRate.FPS_30, BackgroundFrameRate.FPS_60, BackgroundFrameRate.DYNAMIC)) {
                DesktopPerformance.setBackgroundRate(rate)
                val expected = rate.fps ?: 60
                runBlocking { withTimeout(3000) { DesktopPerformance.backgroundFramesPerSecond.first { it == expected } } }
                Thread.sleep(250)
                println("HOST_CADENCE=${DesktopPlayerHost.settings.backgroundFramesPerSecond.value} sameFlow=${DesktopPlayerHost.settings.backgroundFramesPerSecond === DesktopPerformance.backgroundFramesPerSecond}")
                DesktopVisualMetrics.collector.reset()
                val start = System.nanoTime()
                Thread.sleep(1800)
                val seconds = (System.nanoTime() - start) / 1e9
                val samples = DesktopVisualMetrics.collector.snapshot()
                val draws = samples.filter { it.stage == PerformanceStage.BACKGROUND_DRAW }.sumOf { it.count }
                println("NATIVE_WINDOW_BACKGROUND selected=$rate desired=$expected draws=$draws seconds=$seconds actualDrawFps=${draws / seconds} samples=$samples")
                assertTrue(draws > 0, "Real Canvas recording must be observed, independently of animation-clock callbacks")
                assertTrue(draws / seconds in expected * .8..expected * 1.2,
                    "Current production background draw cadence must follow $rate, observed ${draws / seconds}")
            }
        } finally {
            SwingUtilities.invokeAndWait { window.get().dispose() }
            DesktopPerformance.setMode(previousMode)
            DesktopPerformance.setBackgroundRate(previousRate)
            DesktopPerformance.setPlaying(false)
        }
    }
}
