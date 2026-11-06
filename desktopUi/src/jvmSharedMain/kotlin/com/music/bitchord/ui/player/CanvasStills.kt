package com.music.bitchord.ui.player

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

@Immutable
internal class CanvasStill(val url: String, val image: ImageBitmap, val positionMs: Long)

/** One observed clip frame survives the player closing, without retaining a bitmap per song. */
internal object CanvasStills {
    private var current by mutableStateOf<CanvasStill?>(null)
    var fresh by mutableStateOf(false)
        private set

    fun forClip(url: String?): CanvasStill? = current?.takeIf { it.url == url }

    fun capture(url: String?, image: ImageBitmap?, positionMs: Long) {
        if (url == null || image == null || positionMs < 0L) {
            if (current?.url == url) {
                current = null
                fresh = false
            }
            return
        }
        current = CanvasStill(url, image, positionMs)
        fresh = true
    }

    fun resumed(url: String?) {
        if (current?.url == url) fresh = false
    }
}

internal fun DrawScope.drawCanvasStill(image: ImageBitmap) {
    if (size.width <= 0f || size.height <= 0f) return
    val scale = maxOf(size.width / image.width, size.height / image.height)
    val srcWidth = (size.width / scale).roundToInt().coerceIn(1, image.width)
    val srcHeight = (size.height / scale).roundToInt().coerceIn(1, image.height)
    drawImage(
        image = image,
        srcOffset = IntOffset((image.width - srcWidth) / 2, (image.height - srcHeight) / 2),
        srcSize = IntSize(srcWidth, srcHeight),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
    )
}
