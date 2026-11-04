package com.music.bitchord.ui.player

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import com.music.bitchord.ui.LocalVisualActivity
import java.awt.GraphicsEnvironment
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeFalse
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackgroundAlphaRenderingTest {
    @Test fun artworkLayerCrossfadeUsesTheBackgroundCapAndRecreatesAtItsTarget() {
        inWindow { fixture ->
            for (fps in listOf(15, 30, 60)) {
                fixture.cadence.value = fps
                fixture.change { fixture.target.value = 0f }
                Thread.sleep(500)
                DesktopVisualMetrics.collector.reset()
                fixture.change { fixture.target.value = 1f }
                Thread.sleep(550)
                val updates = fixture.updates()
                println("ARTWORK_CROSSFADE fps=$fps actualLayerUpdates=$updates durationMs=360")
                assertTrue(updates.toDouble() in fps * .2..fps * .5 + 3,
                    "The outer artwork layer must use the background clock, not the display's unrestricted clock")
            }
            DesktopVisualMetrics.collector.reset()
            fixture.change { fixture.generation.value++ }
            Thread.sleep(500)
            assertTrue(fixture.updates() <= 1, "A recreated background starts at its target without a redundant fade")
        }
    }

    @Test fun hiddenArtworkDoesNotScheduleFramesAndResumesWithoutAStaleFade() {
        inWindow { fixture ->
            fixture.change { fixture.visible.value = false }
            Thread.sleep(150)
            DesktopVisualMetrics.collector.reset()
            fixture.change { fixture.target.value = 1f }
            Thread.sleep(500)
            assertTrue(fixture.updates() <= 1, "Hidden state can settle once, but must not keep updating a layer")
            assertEquals(0L, DesktopVisualMetrics.collector.snapshot()
                .filter { it.stage == PerformanceStage.BACKGROUND_FRAME_GAP }.sumOf { it.count })
            DesktopVisualMetrics.collector.reset()
            fixture.change { fixture.visible.value = true }
            Thread.sleep(500)
            assertEquals(0L, fixture.updates(), "Revealing settled artwork must not replay the hidden transition")
        }
    }

    private class Fixture {
        val cadence = MutableStateFlow(15)
        val target = mutableStateOf(0f)
        val visible = mutableStateOf(true)
        val generation = mutableStateOf(0)
        fun change(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
        fun updates() = DesktopVisualMetrics.collector.snapshot()
            .filter { it.stage == PerformanceStage.BACKGROUND_CROSSFADE_UPDATE }.sumOf { it.count }
    }

    private fun inWindow(block: (Fixture) -> Unit) {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val fixture = Fixture()
        val previousHost = runCatching { PlayerPlatform.host }.getOrNull()
        val previousMetrics = DesktopVisualMetrics.collector.enabled
        val settings = Proxy.newProxyInstance(PlayerSettingsSource::class.java.classLoader,
            arrayOf(PlayerSettingsSource::class.java)) { _, method, _ ->
            if (method.name == "getBackgroundFramesPerSecond") fixture.cadence
            else error("Unexpected settings request: ${method.name}")
        } as PlayerSettingsSource
        val host = Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, _ ->
            if (method.name == "getSettings") settings else error("Unexpected host request: ${method.name}")
        } as PlayerHost
        PlayerPlatform.install(host)
        DesktopVisualMetrics.collector.setEnabled(true)
        val image = ImageBitmap(8, 8).also {
            Canvas(it).drawRect(Rect(0f, 0f, 8f, 8f), Paint().apply { color = Color.Red })
        }
        val window = AtomicReference<ComposeWindow>()
        try {
            SwingUtilities.invokeAndWait {
                window.set(ComposeWindow().apply {
                    title = "BitChord artwork layer cadence test"
                    setContent {
                        CompositionLocalProvider(LocalVisualActivity provides fixture.visible.value) {
                            key(fixture.generation.value) {
                                val alpha by rememberBackgroundAlpha(fixture.target.value)
                                FullArtworkBlurBackdrop(image, Modifier.backgroundAlphaLayer { alpha })
                            }
                        }
                    }
                    setSize(240, 240)
                    setLocation(80, 80)
                    isAlwaysOnTop = true
                    isVisible = true
                })
            }
            Thread.sleep(600)
            block(fixture)
        } finally {
            SwingUtilities.invokeAndWait { window.get()?.dispose() }
            previousHost?.let(PlayerPlatform::install)
            DesktopVisualMetrics.collector.setEnabled(previousMetrics)
        }
    }
}
