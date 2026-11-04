package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import com.music.bitchord.data.lyrics.CharGrowth
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue
import kotlin.test.assertSame
import kotlin.test.assertNotSame
import kotlin.test.assertEquals
import com.music.bitchord.ui.thaiTextStyle

@OptIn(ExperimentalComposeUiApi::class)
class ThaiGlyphRenderingTest {
    private val fontFile = listOf(
        File("desktopApp/src/main/composeResources/font/wdb_bangna.ttf"),
        File("../desktopApp/src/main/composeResources/font/wdb_bangna.ttf"),
    ).first { it.isFile }
    private val family = FontFamily(Font(fontFile))

    private fun render(line: LyricLine, reference: Boolean, time: Long = 1_501,
        density: Float = 1f, fontScale: Float = 1f, weight: FontWeight = FontWeight.ExtraBold,
        dim: Float = 0f, blur: Float = 0f, referenceText: String = line.text, rangeOnly: Int? = null,
        sceneHeight: Int = 320, glow: Float = 0f, feather: Boolean = false, rowBounds: Boolean = false): BufferedImage {
        var referenceLayout: TextLayoutResult? = null
        val scene = ImageComposeScene((600 * density).toInt(), (sceneHeight * density).toInt(),
            Density(density, fontScale), coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black).padding(start = 70.dp, top = 90.dp)
                    .blur(blur.dp, BlurredEdgeTreatment.Unbounded)) {
                    val style = TextStyle(fontFamily = family, fontSize = 34.sp, lineHeight = 41.sp,
                        fontWeight = weight)
                    if (rangeOnly != null && !reference) {
                        val measurer = rememberTextMeasurer()
                        val ink = LyricInkRuns(measurer, line)
                        var layout: TextLayoutResult? = null
                        Text(line.text, style = style, color = Color.Transparent, onTextLayout = { layout = it },
                            modifier = Modifier.padding(10.dp).drawWithContent {
                                layout?.let { ink.use(it); drawText(ink.range(0, rangeOnly), Color.White) }
                            })
                    } else if (reference) {
                        Text(referenceText, style = style, color = Color.White.copy(alpha = if (time <= line.timeMs) dim else 1f),
                            onTextLayout = { referenceLayout = it },
                            modifier = Modifier.padding(10.dp).drawWithContent {
                                val measured = referenceLayout ?: return@drawWithContent
                                val held = line.growingAt(0)?.takeIf { time in it.startMs..it.restsAtMs }
                                val growth = CharGrowth()
                                held?.sampleInto(0, time, growth)
                                val dx = if (held != null) growth.shift * 34.sp.toPx() else 0f
                                val dy = if (held != null) -growth.rise * 2.dp.toPx() * line.wordFall(0, time)
                                    else -line.wordLift(0, time) * 2.dp.toPx()
                                translate(dx, dy) {
                                    scale(if (held != null) growth.scale else 1f,
                                        if (held != null) growth.scale else 1f,
                                        Offset((measured.getLineLeft(0) + measured.getLineRight(0)) / 2f,
                                            (measured.getLineTop(0) + measured.getLineBottom(0)) / 2f)) { this@drawWithContent.drawContent() }
                                }
                            })
                    } else {
                        SweptLyricLine(line, mutableLongStateOf(time), style, dimAlpha = dim,
                            rise = true, feather = feather, glowRoom = 10.dp, glowAlpha = glow, glowRadius = 4.dp,
                            modifier = if (rowBounds) Modifier.lyricRowInkBounds(line.text, null, null) else Modifier)
                    }
                }
            }
        }
        return try {
            scene.render(0).close()
            scene.render(16_000_000).use { image -> image.encodeToData()!!.use { data ->
                ImageIO.read(ByteArrayInputStream(data.bytes))
            } }
        } finally { scene.close() }
    }

    @Test fun activeRisePreservesThaiGlyphHeadsOutsideTheCaretAdvance() {
        val failures = mutableListOf<String>()
        for (text in listOf("ใ", "ไ", "โ")) {
            val line = LyricLine(1_000, text, listOf(LyricWord(1_000, 1_500, text)))
            val reference = render(line, reference = true)
            val active = render(line, reference = false)
            var expectedInk = 0
            var missingInk = 0
            for (y in 0 until reference.height) for (x in 0 until reference.width) {
                if ((reference.getRGB(x, y) and 255) > 100) {
                    expectedInk++
                    if ((active.getRGB(x, y) and 255) <= 100) missingInk++
                }
            }
            if (missingInk > maxOf(4, expectedInk / 50)) {
                failures += "U+${text[0].code.toString(16)} lost $missingInk/$expectedInk bright pixels"
            }
        }
        assertTrue(failures.isEmpty(), "The active renderer must retain the ordinary font outline: $failures")
    }

    private fun assertRetainsInk(reference: BufferedImage, actual: BufferedImage, label: String, checkExtra: Boolean = false) {
        var ink = 0
        var missing = 0
        var extra = 0
        for (y in 0 until reference.height) for (x in 0 until reference.width) {
            val expected = (reference.getRGB(x, y) and 255) > 100
            val drawn = (actual.getRGB(x, y) and 255) > 100
            if (expected) ink++
            if (expected && !drawn) missing++
            if (!expected && drawn) extra++
        }
        assertTrue(ink > 50, "$label must contain an actual visible reference glyph")
        assertTrue(missing <= maxOf(4, ink / 30), "$label lost $missing/$ink pixels")
        if (checkExtra) assertTrue(extra <= maxOf(4, ink / 30), "$label leaked $extra pixels outside the reference ink")
    }

    @Test fun anIsolatedShapedRangeDoesNotPaintTheRestOfTheParagraph() {
        val line = LyricLine(1_000, "ไกล ใจ โบก", listOf(
            LyricWord(1_000, 1_500, "ไกล"), LyricWord(2_000, 2_500, "ใจ"), LyricWord(3_000, 3_500, "โบก")))
        assertRetainsInk(render(line, true, time = 0, dim = 1f, referenceText = "ไกล"),
            render(line, false, time = 0, dim = 1f, rangeOnly = 3), "isolated first word", checkExtra = true)
    }

    @Test fun densityFontScaleAndWeightPreserveTheCompleteHead() {
        val line = LyricLine(1_000, "โ", listOf(LyricWord(1_000, 1_500, "โ")))
        for (density in listOf(1f, 1.25f, 1.5f, 2f)) for (fontScale in listOf(1f, 2f))
            for (weight in listOf(FontWeight.Normal, FontWeight.ExtraBold)) {
                assertRetainsInk(render(line, true, density = density, fontScale = fontScale, weight = weight),
                    render(line, false, density = density, fontScale = fontScale, weight = weight),
                    "density=$density fontScale=$fontScale weight=$weight")
            }
    }

    @Test fun heldThaiClustersMoveWithoutSeparatingTheirMarks() {
        for (text in listOf("ไ", "น้ำ", "กี่", "กุ้")) {
            val line = LyricLine(1_000, text, listOf(LyricWord(1_000, 4_000, text)))
            assertRetainsInk(render(line, true, time = 2_500, dim = 1f),
                render(line, false, time = 2_500, dim = 1f), "held cluster $text")
        }
    }

    @Test fun standbyKeepsTheExistingBlurredOutline() {
        val line = LyricLine(1_000, "ใคร", listOf(LyricWord(1_000, 1_500, "ใคร")))
        // Alpha 0.8 keeps enough pixels above the coverage threshold after the intentional blur.
        assertRetainsInk(render(line, true, time = 0, dim = .8f, blur = 2f),
            render(line, false, time = 0, dim = .8f, blur = 2f), "standby blur", checkExtra = true)
    }

    @Test fun faintStandbyBlurRetainsTheUpperInkAtLargeTextSize() {
        for (text in listOf("ไ", "ใ", "โ", "เด็ดดอกไม้ซ่อนกลิ่น หอมมาดล้ำเกินใคร")) {
            val line = LyricLine(1_000, text, listOf(LyricWord(1_000, 1_500, text)))
            val reference = render(line, true, time = 0, dim = .28f, blur = 2f, fontScale = 2f, sceneHeight = 700)
            val actual = render(line, false, time = 0, dim = .28f, blur = 2f, fontScale = 2f, sceneHeight = 700)
            var top = reference.height
            for (y in 0 until reference.height) for (x in 0 until reference.width)
                if ((reference.getRGB(x, y) and 255) > 3) top = minOf(top, y)
            assertTrue(top < reference.height, "Faint reference text must be visible")
            var expected = 0L
            var observed = 0L
            for (y in top until minOf(top + 24, reference.height)) for (x in 0 until reference.width) {
                expected += reference.getRGB(x, y) and 255
                observed += actual.getRGB(x, y) and 255
            }
            assertTrue(observed >= expected * .95, "Standby upper ink was clipped: $observed/$expected intensity for $text")
        }
    }

    @Test fun productionTypographyRetainsStandbyInkInsideItsAlphaAndBlurLayers() {
        fun capture(boundedRow: Boolean): BufferedImage {
            val scene = ImageComposeScene(400, 350, Density(1f, 2f), coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme {
                    val style = thaiTextStyle("โ", MaterialTheme.typography.headlineLarge.copy(
                        fontFamily = family, fontSize = 34.sp, lineHeight = 41.sp,
                        fontWeight = FontWeight.ExtraBold, letterSpacing = (-.7).sp))
                    val outsets = rememberLyricRowInkOutsets("โ", null, null, style, 20.dp)
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        if (boundedRow) {
                            Box(Modifier.padding(start = 70.dp, top = 90.dp)) {
                                Box(Modifier.lyricRowEffects(outsets, 2.dp) { alpha = .65f }) {
                                    Text("โ", style = style, color = Color.White.copy(alpha = .45f), modifier = Modifier.padding(10.dp))
                                }
                            }
                        } else {
                            Box(Modifier.fillMaxSize().graphicsLayer { alpha = .65f }.blur(2.dp, BlurredEdgeTreatment.Unbounded)) {
                                Text("โ", style = style, color = Color.White.copy(alpha = .45f),
                                    modifier = Modifier.padding(start = 80.dp, top = 100.dp))
                            }
                        }
                    }
                }
            }
            return try {
                scene.render(0).close()
                scene.render(16_000_000).use { image -> image.encodeToData()!!.use { data ->
                    ImageIO.read(ByteArrayInputStream(data.bytes))
                } }
            } finally { scene.close() }
        }
        val unbounded = capture(false)
        val standby = capture(true)
        var top = unbounded.height
        for (y in 0 until unbounded.height) for (x in 0 until unbounded.width)
            if ((unbounded.getRGB(x, y) and 255) > 3) top = minOf(top, y)
        assertTrue(top < unbounded.height)
        var expected = 0L
        var observed = 0L
        for (y in top until top + 24) for (x in 0 until unbounded.width) {
            expected += unbounded.getRGB(x, y) and 255
            observed += standby.getRGB(x, y) and 255
        }
        assertTrue(observed >= expected * .95,
            "The production standby layers cropped the upper ink: $observed/$expected intensity")
    }

    @Test fun expandedEffectBuffersPreserveTheRowsLayoutAndClickBounds() {
        fun check(expand: Boolean): Rect {
            var bounds = Rect.Zero
            var clicks = 0
            val scene = ImageComposeScene(500, 400, Density(1.5f, 2f), coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme {
                    val style = MaterialTheme.typography.headlineLarge.copy(fontFamily = family,
                        fontSize = 34.sp, lineHeight = 41.sp)
                    val outsets = rememberLyricRowInkOutsets("โ", null, null, style, 20.dp)
                    val properties: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = {
                        alpha = .65f; scaleX = .9f; scaleY = .9f; transformOrigin = TransformOrigin(0f, .5f)
                    }
                    val effects = if (expand) Modifier.lyricRowEffects(outsets, 2.dp, properties)
                        else Modifier.graphicsLayer(properties).blur(2.dp, BlurredEdgeTreatment.Unbounded)
                    Box(Modifier.padding(30.dp)) {
                        Box(Modifier.width(200.dp).then(effects)
                            .onGloballyPositioned { bounds = it.boundsInRoot() }
                            .clickable { clicks++ }.padding(10.dp)) {
                            Text("โ", style = style)
                        }
                    }
                }
            }
            try {
                scene.render(0).close(); scene.render(16_000_000).close()
                assertTrue(bounds.width > 100)
                fun click(at: Offset, time: Long) {
                    scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = time, type = PointerType.Mouse,
                        buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = time + 10, type = PointerType.Mouse,
                        buttons = PointerButtons(), button = PointerButton.Primary)
                    scene.render((time + 20) * 1_000_000).close()
                }
                click(Offset(bounds.left - 4, bounds.center.y), 50)
                assertEquals(0, clicks, "Paint overflow must not enlarge the row's click target")
                click(bounds.center, 100)
                assertEquals(1, clicks)
                return bounds
            } finally { scene.close() }
        }
        val original = check(false)
        val expanded = check(true)
        assertTrue(kotlin.math.abs(original.left - expanded.left) < .01f &&
            kotlin.math.abs(original.top - expanded.top) < .01f &&
            kotlin.math.abs(original.right - expanded.right) < .01f &&
            kotlin.math.abs(original.bottom - expanded.bottom) < .01f,
            "Effect bounds moved the actual row: $original vs $expanded")
    }

    @Test fun requestedPhrasesAndMultilingualWrappingRetainTheirShapedInk() {
        for (text in listOf("เด็ดดอกไม้ซ่อนกลิ่น หอมมาดล้ำเกินใคร", "ใ ไ โ ใจ ไกล ไม่ ใหม่ ไหม ใคร",
            "ใคร English 日本語 😀 ใหม่")) for (fontScale in listOf(1f, 2f)) {
            val line = LyricLine(1_000, text, listOf(LyricWord(1_000, 1_500, text)))
            assertRetainsInk(render(line, true, fontScale = fontScale, sceneHeight = 700),
                render(line, false, fontScale = fontScale, sceneHeight = 700), "phrase fontScale=$fontScale")
        }
    }

    @Test fun enlargedHeadsCanExtendBeyondTheRowsRoundedBackground() {
        val line = LyricLine(1_000, "โ", listOf(LyricWord(1_000, 1_500, "โ")))
        assertRetainsInk(render(line, true, fontScale = 2f),
            render(line, false, fontScale = 2f, rowBounds = true), "enlarged row outline")
    }

    @Test fun heldGlyphsKeepTheirGlowAndTheSweepKeepsItsFeather() {
        val line = LyricLine(1_000, "ใคร", listOf(LyricWord(1_000, 4_000, "ใคร")))
        val plain = render(line, false, time = 2_500, dim = 1f)
        val glowing = render(line, false, time = 2_500, dim = 1f, glow = 1f)
        var haloPixels = 0
        for (y in 0 until plain.height) for (x in 0 until plain.width) {
            if ((plain.getRGB(x, y) and 255) < 20 && (glowing.getRGB(x, y) and 255) > 20) haloPixels++
        }
        assertTrue(haloPixels > 10, "A held note must retain its visible halo: $haloPixels pixels")
        val hard = render(line, false, time = 2_500)
        val feathered = render(line, false, time = 2_500, feather = true)
        var softened = 0
        for (y in 0 until hard.height) for (x in 0 until hard.width) {
            if ((feathered.getRGB(x, y) and 255) > (hard.getRGB(x, y) and 255) + 10) softened++
        }
        assertTrue(softened > 10, "The feather must still reveal ink ahead of the hard sweep: $softened pixels")
    }

    @Test fun settledWrappedRowsReuseTheirLayoutsWithoutRetainingCharacterMasks() {
        var checked = false
        val line = LyricLine(1_000, "ใคร ไกล โบก ใจ ใหม่ ไหม", listOf(LyricWord(1_000, 1_500, "ใคร")))
        val scene = ImageComposeScene(100, 100, Density(1f), coroutineContext = Dispatchers.Unconfined) {
            val measurer = rememberTextMeasurer(cacheSize = 0)
            val layout = measurer.measure(line.text, TextStyle(fontFamily = family, fontSize = 34.sp),
                constraints = Constraints(maxWidth = 100))
            val ink = LyricInkRuns(measurer, line)
            ink.use(layout)
            val end = layout.getLineEnd(0, visibleEnd = false)
            val row = ink.range(0, end)
            val character = ink.range(0, 1)
            ink.settled()
            assertSame(row, ink.range(0, end), "A settled row must not be reshaped on every draw")
            assertNotSame(character, ink.range(0, 1), "The retired character mask must be released")
            checked = true
        }
        try { scene.render(0).close(); assertTrue(checked) } finally { scene.close() }
    }
}
