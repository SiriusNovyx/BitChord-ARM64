package com.music.bitchord.ui.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Data
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class PhosphorIconRenderingTest {
    private val sources = listOf(File("src/commonMain/phosphor"), File("sharedUi/src/commonMain/phosphor"))
        .first { it.isDirectory }
    private val output = File("build/test-artifacts/phosphor").apply { mkdirs() }
    private val getters = AppIcons::class.java.declaredMethods
        .filter { it.parameterCount == 0 && it.returnType == ImageVector::class.java }
        .sortedBy { it.name }

    private fun compose(icon: ImageVector, size: Int, ink: Int, background: Int, rtl: Boolean): BufferedImage {
        val scene = ImageComposeScene(size + 16, size + 16, Density(1f), coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Box(Modifier.fillMaxSize().background(Color(background)), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = icon.name, tint = Color(ink), modifier = Modifier.size(size.dp))
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

    /** SVGDOM is an independent SVG decoder, not the generated Compose path parser. */
    private fun official(icon: ImageVector, size: Int, ink: Int, background: Int, rtl: Boolean): BufferedImage {
        val (_, weight, name) = icon.name.split('.')
        val suffix = if (weight == "fill") "-fill" else ""
        val svg = File(sources, "assets/$weight/$name$suffix.svg").readText()
            .replace("currentColor", "#%06x".format(ink and 0xffffff))
        return Surface.makeRasterN32Premul(size + 16, size + 16).use { surface ->
            surface.canvas.clear(background)
            surface.canvas.translate(8f, 8f)
            if (rtl && icon.autoMirror) {
                surface.canvas.translate(size.toFloat(), 0f)
                surface.canvas.scale(-1f, 1f)
            }
            Data.makeFromBytes(svg.toByteArray()).use { data -> SVGDOM(data).use { dom ->
                dom.setContainerSize(size.toFloat(), size.toFloat())
                dom.render(surface.canvas)
            } }
            surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { data ->
                ImageIO.read(ByteArrayInputStream(data.bytes))
            } }
        }
    }

    private fun compare(actual: BufferedImage, reference: BufferedImage, background: Int, label: String) {
        var ink = 0
        var missing = 0
        var extra = 0
        var expectedArea = 0.0
        var actualArea = 0.0
        var expectedX = 0.0
        var expectedY = 0.0
        var actualX = 0.0
        var actualY = 0.0
        var expectedPeak = 0
        var actualPeak = 0
        var expectedSolid = background
        var actualSolid = background
        fun contrast(color: Int): Int = listOf(0, 8, 16).maxOf {
            abs((color ushr it and 255) - (background ushr it and 255))
        }
        fun nearby(image: BufferedImage, x: Int, y: Int): Boolean =
            (maxOf(0, y - 1)..minOf(image.height - 1, y + 1)).any { yy ->
                (maxOf(0, x - 1)..minOf(image.width - 1, x + 1)).any { xx -> contrast(image.getRGB(xx, yy)) > 30 }
            }
        for (y in 0 until actual.height) for (x in 0 until actual.width) {
            val a = contrast(actual.getRGB(x, y))
            val b = contrast(reference.getRGB(x, y))
            if (a > actualPeak) { actualPeak = a; actualSolid = actual.getRGB(x, y) }
            if (b > expectedPeak) { expectedPeak = b; expectedSolid = reference.getRGB(x, y) }
            actualArea += a; actualX += x * a; actualY += y * a
            expectedArea += b; expectedX += x * b; expectedY += y * b
            if (b > 30) {
                ink++
                if (!nearby(actual, x, y)) missing++
            }
            if (a > 30 && !nearby(reference, x, y)) extra++
        }
        assertTrue(ink > 10, "$label must contain actual visible official ink")
        assertTrue(listOf(0, 8, 16).all { abs((actualSolid ushr it and 255) - (expectedSolid ushr it and 255)) <= 2 },
            "$label must preserve the supplied tint and opacity")
        // Compose 1.10.3 PathParserKt uses arcToBezier/cubicTo; SVGDOM keeps native conics.
        // Allow their subpixel edge coverage, while still checking area, position and both ink envelopes.
        val areaMatches = abs(actualArea - expectedArea) <= expectedArea * 0.05
        val positionMatches = actualArea > 0 && abs(actualX / actualArea - expectedX / expectedArea) <= 0.5 &&
            abs(actualY / actualArea - expectedY / expectedArea) <= 0.5
        val matches = missing <= maxOf(2, ink / 100) && extra <= maxOf(2, ink / 100) && areaMatches && positionMatches
        if (!matches) {
            ImageIO.write(actual, "png", File(output, "$label-actual.png"))
            ImageIO.write(reference, "png", File(output, "$label-official.png"))
        }
        assertTrue(matches, "$label: missing=$missing extra=$extra ink=$ink area=$actualArea/$expectedArea position=$positionMatches")
    }

    @Test fun everySemanticIconRendersTheOfficialSubsetInLightAndDarkThemes() {
        assertEquals(110, getters.size)
        for ((ink, background) in listOf(0xff222222.toInt() to 0xfffafafa.toInt(),
            0xffe7e8eb.toInt() to 0xff17181a.toInt())) {
            val gallery = mutableListOf<Pair<String, BufferedImage>>()
            for (getter in getters) {
                val icon = getter.invoke(AppIcons) as ImageVector
                assertSame(icon, getter.invoke(AppIcons), "Vectors must be cached outside recomposition")
                assertTrue(icon.name.startsWith("Phosphor.regular.") || icon.name.startsWith("Phosphor.fill."))
                val actual = compose(icon, 32, ink, background, false)
                compare(actual, official(icon, 32, ink, background, false), background,
                    getter.name + "-" + background.toUInt())
                gallery += getter.name.removePrefix("get") to actual
            }
            val sheet = BufferedImage(1100, ((gallery.size + 9) / 10) * 70, BufferedImage.TYPE_INT_RGB)
            sheet.createGraphics().apply {
                color = java.awt.Color(background)
                fillRect(0, 0, sheet.width, sheet.height)
                color = java.awt.Color(ink)
                font = java.awt.Font("Dialog", java.awt.Font.PLAIN, 9)
                gallery.forEachIndexed { index, (name, image) ->
                    val x = index % 10 * 110
                    val y = index / 10 * 70
                    drawImage(image, x + 31, y, null)
                    drawString(name, x + 2, y + 61)
                }
                dispose()
            }
            ImageIO.write(sheet, "png", File(output, "gallery-${background.toUInt()}.png"))
        }
    }

    @Test fun selectedHeartStarAndDislikeHaveDistinctOfficialFilledStates() {
        for ((regular, fill) in listOf(AppIcons.Heart to AppIcons.HeartFill,
            AppIcons.Star to AppIcons.StarFill, AppIcons.Dislike to AppIcons.DislikeFill)) {
            assertTrue(regular.name.startsWith("Phosphor.regular."))
            assertTrue(fill.name.startsWith("Phosphor.fill."))
            val a = compose(regular, 48, 0xffdd3055.toInt(), 0xff000000.toInt(), false)
            val b = compose(fill, 48, 0xffdd3055.toInt(), 0xff000000.toInt(), false)
            var changed = 0
            for (y in 0 until a.height) for (x in 0 until a.width) if (a.getRGB(x, y) != b.getRGB(x, y)) changed++
            assertTrue(changed > 100, "Selected ${fill.name} must change actual rendered ink")
        }
    }

    @Test fun directionalIconsMirrorAtExistingDisplaySizesAndKeepTint() {
        for (icon in listOf(AppIcons.ArrowBack, AppIcons.ChevronRight, AppIcons.Login, AppIcons.Logout, AppIcons.Revert)) {
            assertTrue(icon.autoMirror)
            for (size in listOf(24, 48)) {
                val bg = 0xff151515.toInt()
                val ink = 0xff75cbed.toInt()
                val actual = compose(icon, size, ink, bg, true)
                assertEquals(size + 16, actual.width)
                compare(actual, official(icon, size, ink, bg, true), bg, icon.name + "-$size-rtl")
            }
        }
    }
}
