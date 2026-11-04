package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.music.bitchord.data.lyrics.LyricAlignment
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.ui.LocalThaiFontFamily
import com.music.bitchord.ui.LocalVisualActivity
import java.awt.EventQueue
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.Proxy
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class ThaiDuetAlignmentRenderingTest {
    private val family = FontFamily(Font(listOf(
        File("desktopApp/src/main/composeResources/font/wdb_bangna.ttf"),
        File("../desktopApp/src/main/composeResources/font/wdb_bangna.ttf"),
    ).first { it.isFile }))
    private val previousDesktopFamily = FontFamily(
        Font(sourceFont("sf_pro_display_regular.otf")),
        Font(sourceFont("sf_pro_display_bold.otf"), FontWeight.Bold),
        Font(sourceFont("sf_pro_display_heavy.otf"), FontWeight.ExtraBold),
    )

    private fun sourceFont(name: String) = listOf(File("desktopApp/src/main/composeResources/font/$name"),
        File("../desktopApp/src/main/composeResources/font/$name")).first { it.isFile }

    private data class Capture(val image: BufferedImage, val original: Rect, val translation: Rect,
                               val textBounds: Map<String, Rect>, val scrollObserved: Boolean = false,
                               val motionAnchors: List<Pair<Float, Float>> = emptyList())

    @Test fun secondarySingerRetainsThaiTranslationAtTheSameEdgeAsUntimedText() = withHost {
        // The regular-text control already respects the right-hand singer lane.
        assertSameEdge(capture("A second voice", true), capture("A second voice", false), "English control")
        val actual = capture("เสียงของฉันอยู่ตรงนี้", true)
        val reference = capture("เสียงของฉันอยู่ตรงนี้", false)
        save(actual.image, "secondary-thai-timed")
        save(reference.image, "secondary-thai-untimed-reference")
        assertSameEdge(actual, reference, "Thai translation")
    }

    @Test fun singerLanesSurviveTranslationScriptsAndCustomOrFallbackFonts() = withHost {
        for (lane in LyricAlignment.entries) for (custom in listOf(false, true)) {
            for (text in listOf("เสียงของฉันอยู่ตรงนี้", "歌声はここにある", "Our voices stay together")) {
                assertSameEdge(capture(text, true, lane, custom), capture(text, false, lane, custom),
                    "$lane custom=$custom $text", lane)
            }
        }
    }

    @Test fun longThaiTranslationsWrapAtTheSameSingerEdgeAtBothTextSizes() = withHost {
        val text = "เสียงของฉันอยู่ตรงนี้ เราจะร้องเพลงด้วยกัน และยังอยู่เคียงข้างกันเสมอ ".repeat(3).trim()
        for (custom in listOf(false, true)) for (size in listOf(1f, 2f)) {
            val actual = capture(text, true, customFont = custom, fontScale = size)
            val reference = capture(text, false, customFont = custom, fontScale = size)
            assertSameEdge(actual, reference, "wrapped custom=$custom fontScale=$size")
            assertTrue(actual.translation.height > 25 * 1.5f * size * 1.5f,
                "The long translation must genuinely wrap: ${actual.translation}")
            save(actual.image, "wrapped-custom-$custom-size-$size")
        }
    }

    @Test fun previousDesktopFontFallbackAndNewThaiFontRetainTheSameSingerAnchor() = withHost {
        for (custom in listOf(false, true)) {
            val actual = capture("เสียงของฉันอยู่ตรงนี้", true, customFont = custom, desktopLegacy = true)
            val reference = capture("เสียงของฉันอยู่ตรงนี้", false, customFont = custom, desktopLegacy = true)
            assertSameEdge(actual, reference, "SF Pro base with custom Thai=$custom")
            save(actual.image, "desktop-previous-family-custom-$custom")
        }
    }

    @Test fun translationsDoNotMoveOriginalsAndBothSingersKeepTheirOwnLanes() = withHost {
        for (lane in LyricAlignment.entries) {
            val translated = capture("เสียงของฉันอยู่ตรงนี้", true, lane, multipleSingers = true)
            val original = capture("เสียงของฉันอยู่ตรงนี้", false, lane, multipleSingers = true, showTranslation = false)
            assertEquals(original.original.left, translated.original.left, 1f)
            assertEquals(original.original.right, translated.original.right, 1f)
            if (lane == LyricAlignment.End) {
                assertTrue(translated.textBounds.getValue("最初の声").left < translated.original.left,
                    "The first singer must retain the left lane while the second singer uses the right")
            }
        }
    }

    @Test fun translatedBackingVocalsUseTheParentSingerLane() = withHost {
        val actual = capture("เสียงของฉันอยู่ตรงนี้", true, multipleSingers = true, backing = true)
        val reference = capture("เสียงของฉันอยู่ตรงนี้", false, multipleSingers = true, backing = true)
        assertSameEdge(actual, reference, "duet lead")
        assertEquals(reference.textBounds.getValue("เสียงประสานอยู่ข้างกัน").right,
            actual.textBounds.getValue("เสียงประสานอยู่ข้างกัน").right, 1f,
            "A shaped backing translation must share its parent's singer edge")
        save(actual.image, "duet-with-backing")
    }

    @Test fun translationOpeningClosingAndLyricScrollingKeepTheSingerAnchor() = withHost(animations = true) {
        val reference = capture("เสียงของฉันอยู่ตรงนี้", false, multipleSingers = true)
        val moving = capture("เสียงของฉันอยู่ตรงนี้", true, multipleSingers = true, driveAcrossLines = true)
        assertSameEdge(moving, reference, "animated translated singer")
        assertTrue(moving.scrollObserved, "Playback moving to the second singer must exercise real list scrolling")
        assertTrue(moving.motionAnchors.size > 8, "Opening/closing must produce multiple measured frames")
        val inset = reference.original.right - reference.translation.right
        assertTrue(moving.motionAnchors.all { (lead, sub) -> abs((lead - sub) - inset) <= 3f },
            "Translated ink must stay on its parent's edge while the row scales, scrolls and reveals")
        save(moving.image, "animated-duet-after-reopening")
    }

    private fun assertSameEdge(actual: Capture, reference: Capture, label: String,
                               lane: LyricAlignment = LyricAlignment.End) {
        assertEquals(reference.original.right, actual.original.right, 1f, "$label original moved")
        val expected = if (lane == LyricAlignment.End) reference.translation.right else reference.translation.left
        val observed = if (lane == LyricAlignment.End) actual.translation.right else actual.translation.left
        assertEquals(expected, observed, 1f,
            "$label detached from its singer lane: actual=${actual.translation} reference=${reference.translation}")
        assertTrue(actual.translation.top >= actual.original.top, "$label must remain below its original")
    }

    private fun capture(text: String, timed: Boolean, alignment: LyricAlignment = LyricAlignment.End,
                        customFont: Boolean = true, fontScale: Float = 1f,
                        multipleSingers: Boolean = false, showTranslation: Boolean = true,
                        backing: Boolean = false, desktopLegacy: Boolean = false,
                        driveAcrossLines: Boolean = false): Capture {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val originalText = "二人で歌う声"
        val backingLine = if (backing) LyricLine(1_100, "返事の声", listOf(LyricWord(1_100, 10_000, "返事の声"))) else null
        val original = LyricLine(1_000, originalText, listOf(LyricWord(1_000, 1_500, originalText)),
            alignment = alignment, background = backingLine)
        // Deliberately independent translated metadata: the parent row must own the singer lane.
        val sub = LyricLine(1_000, text, if (timed) listOf(LyricWord(1_000, 1_500, text)) else emptyList(),
            timingSource = original.takeIf { timed },
            background = backingLine?.let { LyricLine(1_100, "เสียงประสานอยู่ข้างกัน",
                if (timed) listOf(LyricWord(1_100, 10_000, "เสียงประสานอยู่ข้างกัน")) else emptyList(),
                timingSource = it.takeIf { timed }) })
        val lines = when {
            driveAcrossLines -> (0..7).map { LyricLine(it * 100L, "前の歌声 $it") } + original
            multipleSingers -> listOf(LyricLine(0, "最初の声"), original)
            else -> listOf(original)
        }
        val subs = when {
            driveAcrossLines -> (0..7).map { LyricLine(it * 100L, "เสียงก่อนหน้า $it") } + sub
            multipleSingers -> listOf(LyricLine(0, "เสียงแรก"), sub)
            else -> listOf(sub)
        }
        val position = PlaybackPosition().apply { report(if (driveAcrossLines) 200 else 1_250) }
        val playhead = LyricPlayhead(position, mutableStateOf(0))
        val subtitles = mutableStateOf(subs.takeIf { showTranslation && !driveAcrossLines })
        var scrollObserved = false
        lateinit var scene: ImageComposeScene
        EventQueue.invokeAndWait {
            scene = ImageComposeScene(900, if (text.length > 100) 1400 else 720,
                Density(1.5f, fontScale), coroutineContext = Dispatchers.Main.immediate) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalVisualActivity provides true,
                    LocalThaiFontFamily provides if (customFont) family else null) {
                    val typography = Typography().let { defaults -> if (!desktopLegacy) defaults else defaults.copy(
                        headlineLarge = defaults.headlineLarge.copy(fontFamily = previousDesktopFamily),
                        headlineMedium = defaults.headlineMedium.copy(fontFamily = previousDesktopFamily),
                    ) }
                    MaterialTheme(typography = typography) {
                        Box(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = PLAYER_GUTTER)) {
                            LyricsPanel(lines, subLines = subtitles.value, trackKey = "duet-layout-fixture",
                                playhead = playhead, looking = false, isPlaying = driveAcrossLines, onSeekToLine = {},
                                onScrollingChange = { if (it) scrollObserved = true },
                                controlsOpen = false, onRevealControls = {}, onHideControls = {}, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        var capture: Capture? = null
        val anchors = mutableListOf<Pair<Float, Float>>()
        fun textBounds(): Map<String, Rect> {
            fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
            val bounds = linkedMapOf<String, Rect>()
            scene.semanticsOwners.flatMap { all(it.unmergedRootSemanticsNode) }.forEach { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.forEach { bounds[it.text] = node.boundsInRoot }
            }
            return bounds
        }
        try {
            val start = System.nanoTime()
            if (driveAcrossLines) {
                fun pump(durationMs: Long, at: Long) {
                    val until = System.nanoTime() + durationMs * 1_000_000
                    do {
                        EventQueue.invokeAndWait {
                            position.report(at)
                            scene.render(System.nanoTime()).close()
                            val measured = textBounds()
                            val lead = measured[originalText]
                            val sub = measured[text]
                            if (lead != null && sub != null && lead.height > 0 && sub.height > 0)
                                anchors += lead.right to sub.right
                        }
                        Thread.sleep(16)
                    } while (System.nanoTime() < until)
                }
                pump(180, 200)
                EventQueue.invokeAndWait { subtitles.value = subs }
                pump(750, 1_250)
                EventQueue.invokeAndWait { subtitles.value = null }
                pump(650, 1_250)
                EventQueue.invokeAndWait { subtitles.value = subs }
                pump(750, 1_250)
            } else {
                repeat(5) { frame -> EventQueue.invokeAndWait { scene.render(start + frame * 100_000_000L).close() } }
            }
            EventQueue.invokeAndWait {
                val bounds = textBounds()
                if (!showTranslation) assertTrue(text !in bounds, "Disabled subtitles must not leave a text node")
                scene.render(if (driveAcrossLines) System.nanoTime() else start + 600_000_000L).use { image -> image.encodeToData()!!.use { data ->
                    capture = Capture(ImageIO.read(ByteArrayInputStream(data.bytes)), bounds.getValue(originalText),
                        if (showTranslation) bounds.getValue(text) else Rect.Zero, bounds, scrollObserved, anchors.toList())
                } }
            }
        } finally { EventQueue.invokeAndWait { scene.close() } }
        return requireNotNull(capture)
    }

    private fun save(image: BufferedImage, name: String) {
        val directory = File("build/reports/thai-duet-alignment").apply { mkdirs() }
        ImageIO.write(image, "png", File(directory, "$name.png"))
    }

    private fun withHost(animations: Boolean = false, block: () -> Unit) {
        val installed = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previous = installed.get(null)
        val reduceAnimation = MutableStateFlow(!animations)
        val reduceBlur = MutableStateFlow(false)
        val lyricsBlur = MutableStateFlow(true)
        val settings = Proxy.newProxyInstance(PlayerSettingsSource::class.java.classLoader,
            arrayOf(PlayerSettingsSource::class.java)) { _, method, _ -> when (method.name) {
                "getReduceDynamicBlur" -> reduceBlur
                "getLyricsBlur" -> lyricsBlur
                "getReduceAnimation" -> reduceAnimation
                else -> error("Unexpected settings read: ${method.name}")
            }
        } as PlayerSettingsSource
        val host = Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, _ ->
            if (method.name == "getSettings") settings else error("Unexpected host read: ${method.name}")
        } as PlayerHost
        PlayerPlatform.install(host)
        try { block() } finally { installed.set(null, previous) }
    }
}
