package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.ui.LocalThaiFontFamily
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class LyricFontRenderingTest {
    private fun source(path: String) = listOf(File(path), File("../$path")).first { it.isFile }
    private val vazirmatnFile = source("desktopUi/src/commonMain/composeResources/font/vazirmatn_regular.ttf")
    private val thaiFamily = FontFamily(Font(source("desktopApp/src/main/composeResources/font/wdb_bangna.ttf")))

    @Test fun bundledVazirmatnCoversPersianAndArabicAndCarriesItsLicense() {
        val font = vazirmatnFile.inputStream().use { java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, it) }
        assertEquals("Vazirmatn", font.family)
        assertEquals(-1, font.canDisplayUpTo("سلام دنیا مرحبا بالعالم"))
        assertTrue(source("desktopUi/src/commonMain/composeResources/files/OFL-Vazirmatn.txt")
            .readText().contains("SIL OPEN FONT LICENSE Version 1.1"))
    }

    private fun render(text: String, reference: Boolean): BufferedImage {
        val expected = if (text.any { it in '\u0e00'..'\u0e7f' }) thaiFamily
            else FontFamily(Font(vazirmatnFile))
        val scene = ImageComposeScene(900, 300, Density(1.5f), coroutineContext = Dispatchers.Unconfined) {
            CompositionLocalProvider(LocalThaiFontFamily provides thaiFamily) {
                val base = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 34.sp, lineHeight = 48.sp)
                val selected = lyricTextStyle(text, base)
                if (text == "Latin") assertSame(base, selected)
                if (text.any { it in '\u0e00'..'\u0e7f' }) assertSame(thaiFamily, selected.fontFamily)
                Box(Modifier.fillMaxSize().background(Color.Black).padding(20.dp)) {
                    Text(text, style = if (reference) base.copy(fontFamily = expected) else selected, color = Color.White)
                }
            }
        }
        return try {
            scene.render(0).close()
            scene.render(100_000_000).close()
            scene.render(200_000_000).use { it.encodeToData()!!.use { data ->
                ImageIO.read(ByteArrayInputStream(data.bytes))
            } }
        } finally { scene.close() }
    }

    @Test fun ArabicAndPersianUseVazirmatnWhileThaiAndMixedLinesRetainBangnaAt150Percent() {
        for (text in listOf("سلام دنیا", "مرحبا بالعالم", "ภาษาไทย น้ำ กี่ เนื้อเพลง", "น้ำ سلام")) {
            val expected = render(text, reference = true)
            val actual = render(text, reference = false)
            var ink = 0
            var lost = 0
            for (y in 0 until expected.height) for (x in 0 until expected.width) {
                if ((expected.getRGB(x, y) and 255) > 100) {
                    ink++
                    if ((actual.getRGB(x, y) and 255) <= 100) lost++
                }
            }
            assertTrue(ink > 50, "A visible reference is required: $text")
            assertTrue(lost <= maxOf(4, ink / 100), "$text lost $lost/$ink expected font pixels")
        }
        render("Latin", reference = false)
    }
}
