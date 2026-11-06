package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.ui.LocalThaiFontFamily
import com.music.bitchord.ui.LocalVisualActivity
import com.music.bitchord.ui.thaiTextStyle
import java.awt.EventQueue
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.Proxy
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class ThaiTranslationRenderingTest {
    private val family = FontFamily(Font(listOf(
        File("desktopApp/src/main/composeResources/font/wdb_bangna.ttf"),
        File("../desktopApp/src/main/composeResources/font/wdb_bangna.ttf"),
    ).first { it.isFile }))

    @Test fun translationRevealRetainsInkAtPartialOpacityWithoutChangingItsHeight() {
        fun render(reference: Boolean, open: Float, fontScale: Float, blur: Float): BufferedImage {
            lateinit var scene: ImageComposeScene
            var actualHeight = -1
            var expectedHeight = -1
            EventQueue.invokeAndWait {
                scene = ImageComposeScene(600, 400, Density(1.5f, fontScale), coroutineContext = Dispatchers.Main.immediate) {
                    MaterialTheme {
                        val text = "โ ไ ใ น้ำ กี่"
                        val style = thaiTextStyle(text, MaterialTheme.typography.headlineLarge.copy(
                            fontFamily = family, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold))
                        val outsets = rememberLyricRowInkOutsets(text, null, null, style, 10.dp)
                        val measured = rememberTextMeasurer().measure(text, style)
                        expectedHeight = ((measured.size.height + 15) * open).roundToInt()
                        Box(Modifier.fillMaxSize().background(Color.Black)) {
                            Box(Modifier.fillMaxSize().blur(blur.dp, BlurredEdgeTreatment.Unbounded)) {
                                if (reference) {
                                    Box(Modifier.fillMaxSize().graphicsLayer {
                                        alpha = .85f * open * open
                                        translationY = -(measured.size.height + 10.dp.toPx()) * (1f - open) * .6f
                                    }) {
                                        Text(text, style = style, color = Color.White,
                                            modifier = Modifier.padding(start = 60.dp, top = 44.dp))
                                    }
                                } else {
                                    Box(Modifier.padding(start = 50.dp, top = 50.dp)) {
                                        Text(text, style = style, color = Color.White, modifier = Modifier.width(300.dp)
                                            .onGloballyPositioned { actualHeight = it.size.height }
                                            .revealLyricSubline(mutableStateOf(open), outsets.copy(top = outsets.top + 6.dp))
                                            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                                            .offset(y = (-6).dp)
                                            .lyricRowEffects(outsets, 0.dp) { alpha = .85f })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            var result: BufferedImage? = null
            try {
                EventQueue.invokeAndWait {
                    scene.render(0).close()
                    scene.render(16_000_000).use { image -> image.encodeToData()!!.use {
                        result = ImageIO.read(ByteArrayInputStream(it.bytes))
                    } }
                }
            } finally { EventQueue.invokeAndWait { scene.close() } }
            if (!reference) assertEquals(expectedHeight, actualHeight, "Ink buffers must preserve the reveal's measured height")
            return requireNotNull(result)
        }
        for (open in listOf(.25f, .5f, .75f, 1f)) for (fontScale in listOf(1f, 2f)) for (blur in listOf(0f, 2f)) {
            val reference = render(true, open, fontScale, blur)
            val actual = render(false, open, fontScale, blur)
            var expected = 0L
            var missing = 0L
            for (y in 0 until reference.height) for (x in 0 until reference.width) {
                val value = reference.getRGB(x, y) and 255
                expected += value
                missing += (value - (actual.getRGB(x, y) and 255)).coerceAtLeast(0)
            }
            assertTrue(expected > 100, "A faded reference translation must remain visible")
            assertTrue(missing <= expected * .04,
                "Translation reveal clipped ink: open=$open fontScale=$fontScale blur=$blur missing=$missing/$expected")
        }
    }

    private fun capture(text: String, fontScale: Float): BufferedImage {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val playhead = LyricPlayhead(PlaybackPosition().apply { report(1_501) }, mutableStateOf(0))
        lateinit var scene: ImageComposeScene
        EventQueue.invokeAndWait {
            scene = ImageComposeScene(900, 1000, Density(1.5f, fontScale), coroutineContext = Dispatchers.Main.immediate) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalVisualActivity provides true,
                    LocalThaiFontFamily provides family) {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = PLAYER_GUTTER)) {
                            LyricsPanel(listOf(LyricLine(1_000, "Original")),
                                subLines = listOf(LyricLine(1_000, text)), trackKey = "thai-translation",
                                playhead = playhead, looking = false, isPlaying = false, onSeekToLine = {},
                                controlsOpen = false, onRevealControls = {}, onHideControls = {}, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        var result: BufferedImage? = null
        try {
            val start = System.nanoTime()
            repeat(4) { frame -> EventQueue.invokeAndWait { scene.render(start + frame * 100_000_000L).close() } }
            EventQueue.invokeAndWait {
                scene.render(start + 500_000_000L).use { image -> image.encodeToData()!!.use {
                    result = ImageIO.read(ByteArrayInputStream(it.bytes))
                } }
            }
        } finally { EventQueue.invokeAndWait { scene.close() } }
        return requireNotNull(result)
    }

    @Test fun untimedThaiTranslationRetainsItsOverhangAt150PercentDisplayScaling() {
        val previous = runCatching { PlayerPlatform.host }.getOrNull()
        val settings = Proxy.newProxyInstance(PlayerSettingsSource::class.java.classLoader,
            arrayOf(PlayerSettingsSource::class.java)) { _, method, _ -> when (method.name) {
                "getReduceDynamicBlur" -> MutableStateFlow(false)
                "getLyricsBlur" -> MutableStateFlow(true)
                "getReduceAnimation" -> MutableStateFlow(true)
                else -> error("Unexpected settings read: ${method.name}")
            }
        } as PlayerSettingsSource
        val host = Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, _ ->
            if (method.name == "getSettings") settings else error("Unexpected host read: ${method.name}")
        } as PlayerHost
        PlayerPlatform.install(host)
        try {
            val failures = mutableListOf<String>()
            for (fontScale in listOf(1f, 2f)) for (text in listOf("โ", "ไ", "ใ")) {
                val actual = capture(text, fontScale)
                // An empty first line contains the next line's ascent inside the same alpha buffer.
                // Compare identical production text from the bottom, without changing its font or ink.
                val reference = capture("\n$text", fontScale)
                fun bottom(image: BufferedImage): Int = (image.height - 1 downTo 0).first { y ->
                    (0 until image.width).any { x -> (image.getRGB(x, y) and 255) > 100 }
                }
                val actualBottom = bottom(actual)
                val referenceBottom = bottom(reference)
                var expected = 0
                var missing = 0
                for (dy in 0 until (38 * 1.5f * fontScale).toInt()) for (x in 0 until reference.width) {
                    if ((reference.getRGB(x, referenceBottom - dy) and 255) > 100) {
                        expected++
                        if ((actual.getRGB(x, actualBottom - dy) and 255) <= 100) missing++
                    }
                }
                assertTrue(expected > 30, "A visible reference translation is required")
                if (missing > maxOf(4, expected / 30)) failures += "$text fontScale=$fontScale lost $missing/$expected pixels"
            }
            assertTrue(failures.isEmpty(), "The translation alpha layer clipped its glyph overhang: $failures")
        } finally { previous?.let(PlayerPlatform::install) }
    }
}
