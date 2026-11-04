package com.music.bitchord.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.offset
import com.music.bitchord.data.lyrics.CharGrowth
import com.music.bitchord.data.lyrics.LyricLine
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import com.music.bitchord.ui.thaiTextStyle

internal fun requiresShapedLyricInk(text: String): Boolean = text.any { it in '\u0e00'..'\u0e7f' }

internal fun Modifier.lyricRowInkBounds(lead: String, sub: String?, backing: String?): Modifier =
    if (requiresShapedLyricInk(lead) || sub?.let(::requiresShapedLyricInk) == true ||
        backing?.let(::requiresShapedLyricInk) == true) this
    else clip(RoundedCornerShape(10.dp))

internal data class LyricInkOutsets(val side: Dp = 0.dp, val top: Dp = 0.dp, val bottom: Dp = 0.dp)

@Composable
internal fun rememberLyricRowInkOutsets(lead: String, sub: String?, backing: String?, style: TextStyle,
    sideRoom: Dp): LyricInkOutsets {
    val text = when {
        requiresShapedLyricInk(lead) -> lead
        sub != null && requiresShapedLyricInk(sub) -> sub
        backing != null && requiresShapedLyricInk(backing) -> backing
        else -> return LyricInkOutsets()
    }
    val voice = thaiTextStyle(text, style)
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    return remember(text, voice, measurer, density, sideRoom) {
        val fixed = measurer.measure(text, voice, softWrap = false)
        val natural = measurer.measure(text, voice.copy(lineHeight = TextUnit.Unspecified, lineHeightStyle = null), softWrap = false)
        var top = 0f
        var bottom = 0f
        for (row in 0 until minOf(fixed.lineCount, natural.lineCount)) {
            top = maxOf(top, natural.getLineBaseline(row) - natural.getLineTop(row) -
                (fixed.getLineBaseline(row) - fixed.getLineTop(row)))
            bottom = maxOf(bottom, natural.getLineBottom(row) - natural.getLineBaseline(row) -
                (fixed.getLineBottom(row) - fixed.getLineBaseline(row)))
        }
        with(density) { LyricInkOutsets(sideRoom, ceil(top).toDp(), ceil(bottom).toDp()) }
    }
}

/** The effect buffer owns the ink overflow; the item still reports its original layout and hit bounds. */
internal fun Modifier.lyricRowEffects(outsets: LyricInkOutsets, blur: Dp,
    properties: GraphicsLayerScope.() -> Unit): Modifier {
    val extended = outsets.side > 0.dp || outsets.top > 0.dp || outsets.bottom > 0.dp
    val bounds = if (!extended) this else layout { measurable, constraints ->
        val side = outsets.side.roundToPx()
        val top = outsets.top.roundToPx()
        val bottom = outsets.bottom.roundToPx()
        val child = measurable.measure(constraints.offset(horizontal = side * 2, vertical = top + bottom))
        layout((child.width - side * 2).coerceAtLeast(0), (child.height - top - bottom).coerceAtLeast(0)) {
            child.placeRelative(-side, -top)
        }
    }
    val effects = bounds.graphicsLayer {
        properties()
        if (extended && size.width > 0f && size.height > 0f) {
            val side = outsets.side.toPx()
            val top = outsets.top.toPx()
            val bottom = outsets.bottom.toPx()
            val original = transformOrigin
            transformOrigin = TransformOrigin(
                (side + original.pivotFractionX * (size.width - side * 2)) / size.width,
                (top + original.pivotFractionY * (size.height - top - bottom)) / size.height)
        }
    }.blur(blur, BlurredEdgeTreatment.Unbounded)
    return if (!extended) effects else effects.padding(start = outsets.side, end = outsets.side,
        top = outsets.top, bottom = outsets.bottom)
}

/** Reveal paint may overhang its buffer; the list still measures the original animated height. */
internal fun Modifier.revealLyricSubline(progress: State<Float>, outsets: LyricInkOutsets = LyricInkOutsets()): Modifier =
    layout { measurable, constraints ->
        val side = outsets.side.roundToPx()
        val top = outsets.top.roundToPx()
        val bottom = outsets.bottom.roundToPx()
        val placeable = measurable.measure(constraints.offset(horizontal = side * 2, vertical = top + bottom))
        val width = (placeable.width - side * 2).coerceAtLeast(0)
        val fullHeight = (placeable.height - top - bottom).coerceAtLeast(0)
        val open = progress.value
        layout(width, (fullHeight * open).roundToInt()) {
            placeable.placeWithLayer(-side, -top) {
                translationY = -fullHeight * (1f - open) * .6f
                alpha = open * open
            }
        }
    }.padding(start = outsets.side, end = outsets.side, top = outsets.top, bottom = outsets.bottom)

/** Transparent spans isolate shaped ink; caret rectangles cannot contain overhanging glyphs. */
internal class LyricInkRuns(private val measurer: TextMeasurer, private val line: LyricLine) {
    private var layout: TextLayoutResult? = null
    private val masks = mutableMapOf<Long, TextLayoutResult>()
    private var rowKeys: Set<Long> = emptySet()
    var clusters: List<IntRange> = emptyList()
        private set
    var owners: IntArray = IntArray(0)
        private set

    fun use(measured: TextLayoutResult) {
        if (layout === measured) return
        layout = measured
        masks.clear()
        rowKeys = (0 until measured.lineCount).mapTo(mutableSetOf()) { row ->
            rangeKey(measured.getLineStart(row), measured.getLineEnd(row, visibleEnd = false))
        }
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(line.text) }
        val ranges = mutableListOf<IntRange>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            // Some shaping clusters span several graphemes (for example Thai sara am).
            if (ranges.isNotEmpty() && measured.getBoundingBox(ranges.last().first) == measured.getBoundingBox(start)) {
                ranges[ranges.lastIndex] = ranges.last().first until end
            } else ranges += start until end
            start = end
            end = iterator.next()
        }
        clusters = ranges
        owners = IntArray(ranges.size) { index -> line.wordSpans.indexOfFirst { ranges[index].first in it } }
    }

    fun range(start: Int, end: Int): TextLayoutResult {
        val key = rangeKey(start, end)
        return masks.getOrPut(key) {
            val original = requireNotNull(layout).layoutInput
            val text = AnnotatedString.Builder(line.text).apply {
                if (start > 0) addStyle(SpanStyle(color = Color.Transparent), 0, start)
                if (end < line.text.length) addStyle(SpanStyle(color = Color.Transparent), end, line.text.length)
            }.toAnnotatedString()
            measurer.measure(text, original.style.copy(color = Color.White), original.overflow,
                original.softWrap, original.maxLines, original.placeholders, original.constraints,
                original.layoutDirection, original.density, original.fontFamilyResolver)
        }
    }

    fun settled() {
        // Only an active line needs per-cluster paragraphs. Retired lines retain no animation masks.
        masks.keys.retainAll(rowKeys)
    }

    private fun rangeKey(start: Int, end: Int): Long = (start.toLong() shl 32) or end.toLong()
}

@Composable
internal fun ShapedLyricLine(
    line: LyricLine,
    drawnAt: () -> Long,
    style: TextStyle,
    dimAlpha: Float,
    modifier: Modifier,
    maxLines: Int,
    overflow: TextOverflow,
    glowAlpha: Float,
    glowRadius: Dp,
    glowRoom: Dp,
    feather: Boolean,
    rise: Boolean,
    color: Color,
    peak: Dp,
    wipeFeather: Dp,
    horizontalEdge: (TextLayoutResult, Float, Int) -> Float,
    onTextLayout: (TextLayoutResult) -> Unit,
) {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val runs = remember(line, measurer) { LyricInkRuns(measurer, line) }
    var measured by remember(line, style) { mutableStateOf<TextLayoutResult?>(null) }
    val growth = remember { CharGrowth() }
    val room = Modifier.padding(glowRoom)

    fun DrawScope.paintPass(glow: Boolean, highlight: Boolean) {
        val layout = measured ?: return
        runs.use(layout)
        val now = drawnAt()
        if (!glow && highlight && now <= line.timeMs) return
        if (glow && !line.isGrowing(now)) return
        val lifted = rise && line.isLifted(now)
        val revealed = if (highlight && now < line.endMs) line.revealedChars(now) else line.text.length.toFloat()
        val em = style.fontSize.toPx()
        val liftPx = peak.toPx()

        fun brush(row: Int): Brush? {
            if (!highlight) return SolidColor(color.copy(alpha = if (glow) 1f else dimAlpha))
            if (revealed <= layout.getLineStart(row)) return null
            if (revealed >= layout.getLineEnd(row, visibleEnd = false)) return SolidColor(color)
            val left = layout.getLineLeft(row)
            val right = layout.getLineRight(row)
            val edge = horizontalEdge(layout, revealed, row).coerceIn(left, right)
            val rtl = layout.getParagraphDirection(layout.getLineStart(row)) == ResolvedTextDirection.Rtl
            val fade = if (feather) minOf(wipeFeather.toPx(), if (rtl) right - edge else edge - left) else 0f
            return if (fade > 0f) Brush.horizontalGradient(listOf(color, Color.Transparent),
                startX = edge, endX = if (rtl) edge - fade else edge + fade)
            else Brush.horizontalGradient(0f to color, .5f to color, .5f to Color.Transparent, 1f to Color.Transparent,
                startX = if (rtl) edge + 1f else edge - 1f, endX = if (rtl) edge - 1f else edge + 1f)
        }

        fun paint(tint: Brush, start: Int, end: Int, row: Int, dx: Float, dy: Float, scale: Float, bloom: Float = 1f) {
            if (glow && bloom <= .01f) return
            val from = layout.inkX(start, row)
            val to = layout.inkX(end, row)
            translate(dx, dy) {
                scale(scale, scale, Offset((from + to) / 2f, (layout.getLineTop(row) + layout.getLineBottom(row)) / 2f)) {
                    drawText(runs.range(start, end), tint, alpha = if (glow) bloom else 1f)
                }
            }
        }

        for (row in 0 until layout.lineCount) {
            val start = layout.getLineStart(row)
            val end = layout.getLineEnd(row, visibleEnd = false)
            if (start >= end) continue
            val tint = brush(row) ?: continue
            if (!lifted && !glow) {
                paint(tint, start, end, row, 0f, 0f, 1f)
                continue
            }
            var flatStart = -1
            var flatEnd = -1
            var flatDy = 0f
            fun flush() {
                if (flatStart >= 0) paint(tint, flatStart, flatEnd, row, 0f, flatDy, 1f)
                flatStart = -1
            }
            for (index in runs.clusters.indices) {
                val cluster = runs.clusters[index]
                if (cluster.first !in start until end) continue
                val owner = runs.owners[index]
                val held = line.growingAt(owner)?.takeIf { now in it.startMs..it.restsAtMs }
                if (held != null) {
                    flush()
                    held.sampleInto(cluster.first - line.wordSpans[owner].first, now, growth)
                    paint(tint, cluster.first, cluster.last + 1, row, growth.shift * em,
                        -growth.rise * liftPx * line.wordFall(owner, now), growth.scale, growth.bloom)
                } else if (!glow) {
                    val dy = if (owner < 0) 0f else -line.wordLift(owner, now) * liftPx
                    if (flatStart >= 0 && dy != flatDy) flush()
                    if (flatStart < 0) { flatStart = cluster.first; flatDy = dy }
                    flatEnd = cluster.last + 1
                }
            }
            flush()
        }
        if (!lifted && !line.isGrowing(now)) runs.settled()
    }

    Box(modifier) {
        if (glowAlpha > .01f) Text(line.text, style = style, color = Color.Transparent,
            maxLines = maxLines, overflow = overflow,
            modifier = Modifier.graphicsLayer { alpha = glowAlpha }
                .blur(glowRadius, BlurredEdgeTreatment.Unbounded).then(room)
                .drawWithContent { paintPass(glow = true, highlight = false) })
        Text(line.text, style = style, color = Color.Transparent, maxLines = maxLines, overflow = overflow,
            onTextLayout = { measured = it; onTextLayout(it) },
            modifier = room.drawWithContent {
                paintPass(glow = false, highlight = false)
                paintPass(glow = false, highlight = true)
            })
    }
}

private fun TextLayoutResult.inkX(offset: Int, row: Int): Float {
    val rtl = getParagraphDirection(getLineStart(row)) == ResolvedTextDirection.Rtl
    return when {
        offset <= getLineStart(row) -> if (rtl) getLineRight(row) else getLineLeft(row)
        offset >= getLineEnd(row, visibleEnd = true) -> if (rtl) getLineLeft(row) else getLineRight(row)
        else -> getHorizontalPosition(offset, usePrimaryDirection = true)
    }
}
