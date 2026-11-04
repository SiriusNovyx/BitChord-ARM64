package com.music.bitchord.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Canvas as BitmapCanvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.ui.player.CanvasContentMode
import com.music.bitchord.ui.player.CanvasVideoSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.math.roundToInt

private data class PresentedCanvasFrame(val image: ImageBitmap, val positionMs: Long?)

/**
 * The desktop's decoder behind the shared player's Canvas clip.
 *
 * The phone plays the clip in a silent ExoPlayer on a TextureView; here FFmpeg
 * decodes it into frames that Compose draws. Everything the shared player asks
 * of the clip is answered the same way as on the phone: nothing until a first
 * frame, then a 320ms fade reported through `onCoverChanged`, the aspect once
 * known, frames for the backdrop to re-tint off, the portrait fit, the bottom
 * dissolve, and a pause that keeps the last frame on screen.
 */
@Composable
internal fun DesktopCanvasVideo(
    spec: CanvasVideoSpec,
    modifier: Modifier,
    visibilityPolicy: StateFlow<DesktopVisualPolicy> = DesktopPerformance.policy,
) {
    val canvas = spec.canvas
    val visualPolicy by visibilityPolicy.collectAsState()
    var displayed by remember(canvas) { mutableStateOf<PresentedCanvasFrame?>(null) }
    val frame = displayed?.image
    val latestFrame by rememberUpdatedState(frame)
    val rendered = frame != null
    var matchedStart by remember(canvas) { mutableStateOf(true) }
    var firstFrameSettled by remember(canvas) { mutableStateOf(false) }
    val request by rememberUpdatedState(CanvasPlaybackRequest(spec.isPlaying, spec.pausedForTransition,
        visualPolicy.animateArtwork, spec.resumeDelayMs))
    val startPosition by rememberUpdatedState(spec.startPositionMs)
    val reportAspect by rememberUpdatedState(spec.onAspectRatioChanged)
    val reportRendered by rememberUpdatedState(spec.onRenderedChanged)
    val reportFrame by rememberUpdatedState(spec.onFrameCaptured)
    val reportCover by rememberUpdatedState(spec.onCoverChanged)
    val reportStill by rememberUpdatedState(spec.onStillCaptured)
    val reportResumed by rememberUpdatedState(spec.onResumed)
    val presentationAlpha by rememberUpdatedState(spec.presentationAlpha)

    LaunchedEffect(canvas, visualPolicy.animateArtwork) {
        firstFrameSettled = false
        if (!visualPolicy.animateArtwork) {
            firstFrameSettled = true
            return@LaunchedEffect
        }
        try {
            withDesktopCanvasResource(DesktopCanvasDecoder()) { decoder ->
                val opened = withContext(Dispatchers.IO) {
                    var last: Result<Unit> = Result.failure(IllegalStateException("the clip could not be fetched"))
                    for (candidate in listOfNotNull(canvas.url, canvas.fallbackUrl)) {
                        val source = if (isManifest(candidate)) {
                            candidate
                        } else {
                            DesktopCanvasCache.fileFor(candidate)?.toAbsolutePath()?.toString() ?: continue
                        }
                        last = decoder.open(source)
                        if (last.isSuccess) break
                    }
                    last
                }
                if (opened.isFailure) {
                    DesktopTrackLog.log("canvas: ${opened.exceptionOrNull()?.message}")
                    return@LaunchedEffect
                }
                reportAspect(decoder.width.toFloat() / decoder.height.coerceAtLeast(1))
                val pixels = ByteArray(decoder.width * decoder.height * 4)
                val info = ImageInfo.makeN32(decoder.width, decoder.height, ColorAlphaType.OPAQUE)
                val resumeAt = displayed?.positionMs ?: startPosition
                val sought = resumeAt <= 0 || withContext(Dispatchers.IO) { decoder.seekTo(resumeAt) }
                suspend fun decode(): Pair<ByteArray, Long?>? = withContext(Dispatchers.IO) {
                    if (!decoder.nextFrame(pixels)) null else pixels.copyOf() to decoder.positionMs
                }
                fun present(decoded: Pair<ByteArray, Long?>) {
                    val image = Image.makeRaster(info, decoded.first, decoder.width * 4).toComposeImageBitmap()
                    displayed = PresentedCanvasFrame(image, decoded.second)
                }
                // A paused/remounted clip still paints its matching first frame.
                val first = decode()
                if (first == null) {
                    DesktopTrackLog.log("canvas: the clip decoded no frames; not looping it")
                    return@LaunchedEffect
                }
                matchedStart = resumeAt <= 0 || (sought && first.second == resumeAt)
                if (!matchedStart) reportStill(null, 0L)
                present(first)
                firstFrameSettled = true
                playDesktopCanvasFrames(snapshotFlow { request }, intervalMillis = { decoder.frameIntervalMillis },
                    decode = ::decode, present = ::present, onResumed = { reportResumed() },
                    initialFramePresented = true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            DesktopTrackLog.log("canvas: ${failure.message}")
        } finally {
            firstFrameSettled = true
        }
    }

    LaunchedEffect(canvas, spec.captureStill, spec.pausedForTransition) {
        if (spec.captureStill && spec.pausedForTransition) {
            val still = snapshotFlow { displayed to firstFrameSettled }
                .first { it.first != null || it.second }.first
            reportStill(still?.image?.takeIf { still.positionMs != null }, still?.positionMs ?: 0L)
        }
    }

    LaunchedEffect(rendered) { reportRendered(rendered) }

    // One frame once there is one, and then — for a caller re-tinting off the
    // clip — another every so often.
    LaunchedEffect(rendered, spec.refreshFrameEveryMs, spec.frameCapturePx, visualPolicy.animateArtwork) {
        if (!rendered || !visualPolicy.animateArtwork || spec.frameCapturePx <= 0) return@LaunchedEffect
        latestFrame?.canvasBackdropCapture(spec.frameCapturePx)?.let(reportFrame)
        val interval = spec.refreshFrameEveryMs ?: return@LaunchedEffect
        while (true) {
            delay(interval)
            latestFrame?.canvasBackdropCapture(spec.frameCapturePx)?.let(reportFrame)
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (rendered) 1f else 0f,
        animationSpec = tween(durationMillis = if (spec.revealInstantly && matchedStart) 0 else 320),
        label = "canvasAlpha",
    )
    LaunchedEffect(Unit) {
        snapshotFlow { alpha * presentationAlpha() }.collect { reportCover(it) }
    }
    DisposableEffect(Unit) {
        onDispose {
            reportRendered(false)
            reportCover(0f)
        }
    }

    val bottomFade = spec.bottomFade
    val bottomFadeEndPx = spec.bottomFadeEndPx
    Canvas(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha * presentationAlpha()
            // Offscreen so the bottom dissolve masks the clip, not what is behind it.
            if (bottomFade > 0.001f) compositingStrategy = CompositingStrategy.Offscreen
        },
    ) {
        val image = frame ?: return@Canvas
        val clipAspect = image.width.toFloat() / image.height
        val viewAspect = size.width / size.height
        val fit = spec.contentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect < 1f
        val (drawWidth, drawHeight) = when {
            fit && clipAspect > viewAspect -> size.width to size.width / clipAspect
            fit -> size.height * clipAspect to size.height
            clipAspect > viewAspect -> size.height * clipAspect to size.height
            else -> size.width to size.width / clipAspect
        }
        val left = (size.width - drawWidth) / 2f
        val top = if (fit && spec.alignPortraitTop) 0f else (size.height - drawHeight) / 2f
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt()),
        )
        if (bottomFade > 0.001f) {
            val endY = bottomFadeEndPx?.coerceIn(0f, size.height) ?: size.height
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black,
                    1f to Color.Transparent,
                    startY = endY * (1f - bottomFade.coerceAtMost(1f)),
                    endY = endY,
                ),
                topLeft = Offset.Zero,
                size = size,
                blendMode = BlendMode.DstIn,
            )
        }
    }
}

internal fun ImageBitmap.canvasBackdropCapture(maxEdge: Int): ImageBitmap? {
    if (maxEdge <= 0) return null
    val scale = minOf(1f, maxEdge.toFloat() / maxOf(width, height))
    if (scale == 1f) return this
    val target = ImageBitmap((width * scale).roundToInt().coerceAtLeast(1),
        (height * scale).roundToInt().coerceAtLeast(1))
    BitmapCanvas(target).drawImageRect(this, IntOffset.Zero, IntSize(width, height), IntOffset.Zero,
        IntSize(target.width, target.height), Paint().apply { filterQuality = FilterQuality.Low })
    return target
}

/** The desktop's clip, in the shared player's terms. */
internal fun DesktopCanvasArtwork.toShared(): CanvasArtwork = CanvasArtwork(
    url = url,
    fallbackUrl = fallbackUrl,
    title = title,
    artist = artist,
    album = album,
    source = if (source == DesktopCanvasSource.SPOTIFY) CanvasSource.SPOTIFY else CanvasSource.OTHER,
)
