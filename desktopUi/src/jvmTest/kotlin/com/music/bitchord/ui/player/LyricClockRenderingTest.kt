package com.music.bitchord.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.ui.LocalVisualActivity
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class LyricClockRenderingTest {
    @Test(timeout = 10_000) fun visibleUnfocusedLyricsDrawTheReportedPlaybackPosition() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.STARTED }
        }
        val position = PlaybackPosition()
        val playhead = LyricPlayhead(position, mutableStateOf(0))
        val line = LyricLine(0, "Timed fixture", listOf(LyricWord(0, 10_000, "Timed fixture")))
        val schedule = LyricFrameSchedule(listOf(line), false)
        var drawnPosition = -1L
        val scene = ImageComposeScene(200, 100, Density(1f), coroutineContext = Dispatchers.Unconfined) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalVisualActivity provides true) {
                val clock = rememberLyricClock("fixture", playhead, true, schedule)
                Canvas(Modifier.fillMaxSize()) {
                    drawnPosition = clock.longValue
                    drawRect(Color.White, size = Size(clock.longValue.toFloat() / 10, 20f))
                }
            }
        }
        try {
            val start = System.nanoTime()
            while (System.nanoTime() - start < 1_200_000_000L) {
                val now = System.nanoTime()
                position.report((now - start) / 1_000_000, now)
                scene.render(now).close()
                Thread.sleep(16)
            }
            assertTrue(drawnPosition > 800,
                "Visible lyrics must draw advancing playback even while the desktop window lacks focus")
        } finally { scene.close() }
    }
}
