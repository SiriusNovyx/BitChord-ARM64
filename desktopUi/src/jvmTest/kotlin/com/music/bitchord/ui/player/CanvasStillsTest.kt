package com.music.bitchord.ui.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas as BitmapCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CanvasStillsTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun movingSleeveDrawsTheCapturedFrameWithCenteredCrop() {
        val image = ImageBitmap(8, 4)
        val canvas = BitmapCanvas(image)
        canvas.drawRect(Rect(0f, 0f, 8f, 4f), Paint().apply { color = Color.Red })
        canvas.drawRect(Rect(0f, 0f, 2f, 4f), Paint().apply { color = Color.Blue })
        canvas.drawRect(Rect(6f, 0f, 8f, 4f), Paint().apply { color = Color.Green })
        val scene = ImageComposeScene(4, 4, Density(1f), coroutineContext = Dispatchers.Unconfined) {
            Canvas(Modifier.fillMaxSize()) { drawCanvasStill(image) }
        }
        try {
            scene.render().use { rendered ->
                val pixels = rendered.toComposeImageBitmap().toPixelMap()
                for (x in 0 until 4) for (y in 0 until 4) assertEquals(Color.Red, pixels[x, y])
            }
        } finally {
            scene.close()
        }
    }
    @Test fun capturedStillIsBoundToItsClipAndObservedPosition() {
        val image = ImageBitmap(4, 6)
        CanvasStills.capture("clip-a", image, 750L)
        val still = requireNotNull(CanvasStills.forClip("clip-a"))
        assertSame(image, still.image)
        assertEquals(750L, still.positionMs)
        assertTrue(CanvasStills.fresh)
        assertNull(CanvasStills.forClip("clip-b"))
        assertNull(CanvasStills.forClip(null))
        CanvasStills.capture("clip-a", null, 0L)
    }

    @Test fun onlyTheMatchingResumedClipCanRetireItsFreshFrame() {
        CanvasStills.capture("clip-b", ImageBitmap(2, 2), 375L)
        CanvasStills.resumed("clip-a")
        assertTrue(CanvasStills.fresh)
        CanvasStills.resumed("clip-b")
        assertFalse(CanvasStills.fresh)
        assertEquals(375L, CanvasStills.forClip("clip-b")?.positionMs)
        CanvasStills.capture("clip-b", null, 0L)
    }

    @Test fun replacementKeepsOnlyOneStillAndAnOldFailureCannotEraseIt() {
        CanvasStills.capture("clip-a", ImageBitmap(2, 2), 100L)
        val next = ImageBitmap(3, 2)
        CanvasStills.capture("clip-b", next, 200L)
        assertNull(CanvasStills.forClip("clip-a"))
        CanvasStills.capture("clip-a", null, 0L)
        assertSame(next, CanvasStills.forClip("clip-b")?.image)
        CanvasStills.capture("clip-b", null, 0L)
        assertNull(CanvasStills.forClip("clip-b"))
        assertFalse(CanvasStills.fresh)
    }
}
