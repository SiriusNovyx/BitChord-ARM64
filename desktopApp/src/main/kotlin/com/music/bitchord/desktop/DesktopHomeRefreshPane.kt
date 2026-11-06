package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Snackbar
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.LocalPullToRefreshEnabled
import com.music.bitchord.ui.components.RefreshPullGesture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopHomeRefreshPane(
    listState: LazyListState,
    refreshing: Boolean,
    result: HomeRefreshResult,
    contentPadding: PaddingValues,
    onRefresh: () -> Unit,
    content: @Composable (PullToRefreshState, () -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    val threshold = with(density) { 96.dp.toPx() }
    val gesture = remember(threshold) { RefreshPullGesture(threshold) }
    val pullState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    val refresh by rememberUpdatedState(onRefresh)
    var distance by remember { mutableFloatStateOf(0f) }
    var idleJob by remember { mutableStateOf<Job?>(null) }
    var showResult by remember { mutableStateOf(false) }
    var previousResult by remember { mutableStateOf(result) }
    var touchReleased by remember { mutableStateOf(false) }
    var wheelArmed by remember { mutableStateOf(false) }
    var wheelReady by remember { mutableStateOf(false) }
    LaunchedEffect(wheelReady) {
        if (wheelReady) { delay(2_000); wheelReady = false }
    }
    LaunchedEffect(result) {
        val changed = result != previousResult
        previousResult = result
        showResult = changed && (result == HomeRefreshResult.UPDATED || result == HomeRefreshResult.OFFLINE || result == HomeRefreshResult.ERROR)
        if (showResult) { delay(4_000); showResult = false }
    }
    LaunchedEffect(refreshing) {
        if (refreshing) { idleJob?.cancel(); gesture.cancel(); distance = 0f }
    }
    DisposableEffect(gesture) {
        onDispose { idleJob?.cancel(); gesture.cancel() }
    }
    val wheelFraction = (distance / threshold).coerceAtMost(if (wheelArmed) 1.5f else .95f)
    val pull = maxOf(wheelFraction, pullState.distanceFraction).coerceIn(0f, 1.5f)
    val reveal by animateFloatAsState(
        with(density) { (if (refreshing || wheelReady) 48.dp else 48.dp * pull).toPx() },
        tween(160), label = "Home refresh reveal",
    )
    Box(
        Modifier.fillMaxSize()
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                idleJob?.cancel(); gesture.cancel(); distance = 0f; wheelArmed = false; wheelReady = false
                if (event.changes.any { it.type == PointerType.Touch }) touchReleased = false
            }
            .onPointerEvent(PointerEventType.Release, PointerEventPass.Initial) { event ->
                if (event.changes.any { it.type == PointerType.Touch }) touchReleased = true
            }
            .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Final) { event ->
            if (refreshing || event.changes.any { it.isConsumed }) {
                idleJob?.cancel(); gesture.cancel(); distance = 0f; wheelArmed = false; wheelReady = false
                return@onPointerEvent
            }
            val atTop = !listState.canScrollBackward
            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
            if (!atTop || (delta >= 0 && distance == 0f)) {
                idleJob?.cancel(); gesture.cancel(); distance = 0f; wheelArmed = false; wheelReady = false
                return@onPointerEvent
            }
            gesture.wheel(-delta * with(density) { 40.dp.toPx() }, atTop, System.nanoTime() / 1_000_000)
            distance = gesture.distance
            wheelArmed = gesture.wheelArmed
            wheelReady = false
            event.changes.forEach { it.consume() }
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(gesture.wheelIdleDelayMillis)
                val trigger = gesture.wheelIdle(System.nanoTime() / 1_000_000)
                distance = gesture.distance
                wheelArmed = false
                wheelReady = gesture.wheelReady
                if (trigger) refresh()
            }
        },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = reveal }) {
            CompositionLocalProvider(LocalPullToRefreshEnabled provides true) {
                content(pullState) {
                    // Material3 also settles on cancellation. Only an observed touch UP may refresh.
                    if (touchReleased && !refreshing) { touchReleased = false; refresh() }
                }
            }
        }
        if (pull > 0f || refreshing || wheelReady) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = contentPadding.calculateTopPadding() + 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (refreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    when {
                        refreshing -> "Refreshing Home…"
                        wheelArmed -> "Stop scrolling to refresh"
                        distance > 0f -> "Keep scrolling up to refresh"
                        pull >= 1f -> "Release to refresh"
                        wheelReady -> "At top · scroll up again to refresh"
                        else -> "Pull to refresh"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (showResult) {
            Snackbar(
                Modifier.align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 8.dp)
                    .widthIn(max = 480.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(when (result) {
                    HomeRefreshResult.OFFLINE -> "Offline · reconnect to refresh Home"
                    HomeRefreshResult.ERROR -> "Couldn’t refresh · try again"
                    else -> "Home updated"
                })
            }
        }
    }
}
