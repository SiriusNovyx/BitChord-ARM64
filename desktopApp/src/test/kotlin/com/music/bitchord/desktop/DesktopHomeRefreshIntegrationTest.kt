package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.PullToRefresh
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DesktopHomeRefreshIntegrationTest {
    @Test fun `wheel idle forces fresh Home through the controller while retaining old content`() {
        Fixture().use { fixture ->
            assertTrue(fixture.refresh.request(IDENTITY))
            fixture.pumpFor(30)
            fixture.assertText("Old Home")
            fixture.controllerNow = 100L
            assertFalse(fixture.refresh.request(IDENTITY), "Ordinary navigation reuses fresh content")
            assertEquals(1, fixture.fetches.get())

            fixture.prepareAndArm()
            assertEquals(0, fixture.wheelRefreshes.get(), "An armed wheel gesture still waits for idle")
            fixture.pumpUntil { fixture.refresh.state.value.refreshing }
            assertEquals(1, fixture.wheelRefreshes.get())
            assertEquals(2, fixture.fetches.get(), "Wheel refresh must force a fetch despite the fresh timestamp")
            assertEquals("Old Home", fixture.refresh.state.value.content)
            fixture.assertText("Old Home")

            assertFalse(fixture.refresh.request(IDENTITY, force = true), "The held request must be deduplicated")
            repeat(4) { fixture.wheel(-1f); fixture.pumpFor(110) }
            fixture.pumpFor(300)
            assertEquals(2, fixture.fetches.get())
            assertEquals(1, fixture.wheelRefreshes.get(), "Refreshing input must not start a second gesture")

            fixture.answer.complete("Updated Home")
            fixture.pumpUntil { fixture.refresh.state.value.content == "Updated Home" }
            assertFalse(fixture.refresh.state.value.refreshing)
            assertEquals(HomeRefreshResult.UPDATED, fixture.refresh.state.value.result)
            fixture.assertText("Updated Home")
            assertEquals(2, fixture.fetches.get())
        }
    }

    @Test fun `reversing an armed wheel gesture before idle never reaches the controller`() {
        Fixture().use { fixture ->
            fixture.refresh.request(IDENTITY)
            fixture.pumpFor(30)
            fixture.prepareAndArm()
            fixture.wheel(1f)
            fixture.pumpFor(350)
            assertEquals(0, fixture.wheelRefreshes.get())
            assertEquals(1, fixture.fetches.get())
            assertFalse(fixture.refresh.state.value.refreshing)
            fixture.assertText("Old Home")
        }
    }

    private class Fixture : AutoCloseable {
        val fetches = AtomicInteger()
        val wheelRefreshes = AtomicInteger()
        val answer = CompletableDeferred<String>()
        var controllerNow = 0L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val refresh = DesktopHomeRefresh(scope, { controllerNow }) { _: String ->
            if (fetches.incrementAndGet() == 1) "Old Home" else answer.await()
        }
        private val list = LazyListState()
        private val startedAt = System.nanoTime()
        private val scene = ImageComposeScene(400, 400, coroutineContext = Dispatchers.Unconfined) {
            val snapshot by refresh.state.collectAsState()
            MaterialTheme {
                DesktopHomeRefreshPane(list, snapshot.refreshing, snapshot.result, PaddingValues(), {
                    wheelRefreshes.incrementAndGet()
                    refresh.request(IDENTITY, force = true)
                }) { state, request ->
                    PullToRefresh(snapshot.refreshing, request, state) {
                        LazyColumn(Modifier.fillMaxSize(), state = list) {
                            item { Text(snapshot.content.orEmpty()) }
                            items(20) { Box(Modifier.height(60.dp)) }
                        }
                    }
                }
            }
        }

        fun prepareAndArm() {
            wheel(-3f)
            pumpFor(300)
            assertEquals(0, wheelRefreshes.get(), "The first burst only prepares the settled top")
            repeat(4) { step ->
                wheel(-1f)
                if (step < 3) pumpFor(110)
            }
        }

        fun wheel(delta: Float) {
            render()
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(100f, 100f),
                timeMillis = (System.nanoTime() - startedAt) / 1_000_000,
                scrollDelta = Offset(0f, delta))
            render()
        }

        fun pumpFor(millis: Long) {
            val deadline = System.nanoTime() + millis * 1_000_000
            do { render(); Thread.sleep(10) } while (System.nanoTime() < deadline)
            render()
        }

        fun pumpUntil(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 2_000_000_000
            while (!condition() && System.nanoTime() < deadline) pumpFor(10)
            render()
            assertTrue(condition(), "Home did not reach the expected state within two seconds")
        }

        fun assertText(text: String) {
            render()
            fun matches(node: SemanticsNode): Boolean =
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
                    node.children.any(::matches)
            assertTrue(scene.semanticsOwners.any { matches(it.rootSemanticsNode) }, "Visible Home content: $text")
        }

        private fun render() { scene.render(System.nanoTime() - startedAt).close() }
        override fun close() { scene.close(); refresh.close(); scope.cancel() }
    }

    companion object { private const val IDENTITY = "synthetic-account/profile/session" }
}
