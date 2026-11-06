package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.PullToRefresh
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalMaterial3Api::class)
class DesktopHomePaneTest {
    @Test fun `reopening Home does not replay a finished refresh or shift its content`() {
        verifyFeedbackDoesNotMoveContent(initiallyLoading = false)
    }

    @Test fun `refresh completion settles content without a delayed feedback jump`() {
        verifyFeedbackDoesNotMoveContent(initiallyLoading = true)
    }

    private fun verifyFeedbackDoesNotMoveContent(initiallyLoading: Boolean) {
        val state = mutableStateOf(HomeRefreshSnapshot<Int>(
            refreshing = initiallyLoading,
            result = if (initiallyLoading) HomeRefreshResult.LOADING else HomeRefreshResult.UPDATED,
        ))
        var content: LayoutCoordinates? = null
        val image = ImageComposeScene(400, 400, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                DesktopHomeRefreshPane(LazyListState(), state.value.refreshing, state.value.result,
                    PaddingValues(), {}) { _, _ ->
                    Box(Modifier.fillMaxSize().onGloballyPositioned { content = it })
                }
            }
        }
        var frame = 0L
        fun settle() {
            repeat(20) {
                image.render(frame * 16_000_000).close()
                frame++
                Thread.sleep(16)
            }
        }
        try {
            settle()
            if (initiallyLoading) {
                state.value = HomeRefreshSnapshot(result = HomeRefreshResult.UPDATED)
                settle()
            }
            assertEquals(0f, checkNotNull(content).positionInRoot().y, .5f,
                "Finished feedback must not hold or replay a 48dp content translation")
        } finally { image.close() }
    }

    @Test fun `touch pull waits for release and cancellation never refreshes`() {
        for (cancel in listOf(false, true)) {
            val calls = AtomicInteger()
            val list = LazyListState()
            val image = ImageComposeScene(400, 400, coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme {
                    DesktopHomeRefreshPane(list, false, HomeRefreshResult.IDLE, PaddingValues(), { calls.incrementAndGet() }) { state, refresh ->
                        PullToRefresh(false, refresh, state) {
                            LazyColumn(Modifier.fillMaxSize(), state = list) { items(20) { Box(Modifier.height(60.dp)) } }
                        }
                    }
                }
            }
            try {
                image.render(0).close()
                val scene = ImageComposeScene::class.java.getDeclaredField("scene").apply { isAccessible = true }.get(image) as ComposeScene
                val bridge = DesktopTouchScene(scene)
                val stream = DesktopTouchStream()
                bridge.dispatch(stream.accept(0, 1, 100f, 20f, 100, 96)!!)
                bridge.dispatch(stream.accept(1, 1, 100f, 40f, 120, 96)!!)
                bridge.dispatch(stream.accept(1, 1, 100f, 180f, 140, 96)!!)
                bridge.dispatch(stream.accept(1, 1, 100f, 360f, 160, 96)!!)
                image.render(170_000_000).close()
                assertEquals(0, calls.get(), "pull must wait for release")
                if (cancel) bridge.cancel() else bridge.dispatch(stream.accept(2, 1, 100f, 360f, 180, 96)!!)
                for (frame in 190L..900L step 10) image.render(frame * 1_000_000).close()
                assertEquals(if (cancel) 0 else 1, calls.get(), "cancel=$cancel")
            } finally { image.close() }
        }
    }

    @Test fun `wheel overscroll requires preparation and deliberate arming before idle`() {
        val calls = AtomicInteger()
        val list = LazyListState()
        val image = ImageComposeScene(400, 400, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                DesktopHomeRefreshPane(list, false, HomeRefreshResult.IDLE, PaddingValues(), { calls.incrementAndGet() }) { state, refresh ->
                    PullToRefresh(false, refresh, state) {
                        LazyColumn(Modifier.fillMaxSize(), state = list) { items(20) { Box(Modifier.height(60.dp)) } }
                    }
                }
            }
        }
        try {
            image.render(0).close()
            image.sendPointerEvent(PointerEventType.Scroll, Offset(100f, 100f), timeMillis = 100,
                scrollDelta = Offset(0f, -3f))
            assertEquals(0, calls.get(), "wheel has no release event; refresh must wait for idle")
            Thread.sleep(300)
            image.render(400_000_000).close()
            assertEquals(0, calls.get(), "The first burst only prepares an already settled top")
            for (step in 0..3) {
                image.sendPointerEvent(PointerEventType.Scroll, Offset(100f, 100f), timeMillis = 500L + step * 110,
                    scrollDelta = Offset(0f, -1f))
                image.render((500L + step * 110) * 1_000_000).close()
                Thread.sleep(110)
            }
            assertEquals(0, calls.get(), "Armed wheel input still waits for completion by idle")
            Thread.sleep(300)
            image.render(1_200_000_000).close()
            assertEquals(1, calls.get())
            Thread.sleep(300)
            image.render(1_500_000_000).close()
            assertEquals(1, calls.get(), "one gesture must not start repeated refreshes")
        } finally { image.close() }
    }
}
