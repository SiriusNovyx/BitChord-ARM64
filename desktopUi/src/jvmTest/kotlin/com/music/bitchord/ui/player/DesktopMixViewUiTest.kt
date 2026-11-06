package com.music.bitchord.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.music.bitchord.data.settings.MixBlend
import com.music.bitchord.data.settings.MixDeck
import com.music.bitchord.data.settings.MixTransitionView
import com.music.bitchord.data.settings.MixViewTimeline
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Locale
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DesktopMixViewUiTest {
    @Test fun realMixingLabelPointerClickOpensTheActualDeckPopup() {
        for (diagnostics in listOf(false, true)) inScene(diagnostics = diagnostics) { fixture ->
            val target = assertNotNull(fixture.mixingTarget(), "The real Mixing label needs a click action")
            fixture.click(target.boundsInRoot.center)
            fixture.advance(500)
            assertTrue(MixViewRequest.open)
            assertTrue("Outgoing fixture" in fixture.text())
            assertTrue("Incoming fixture" in fixture.text())
        }
    }

    @Test fun completedBlendKeepsItsDecksUntilTheReaderDismissesThem() = inScene(open = true) { fixture ->
        assertTrue("Outgoing fixture" in fixture.text())
        fixture.view.value = null
        fixture.advance(500)
        assertTrue(MixViewRequest.open)
        assertTrue("Outgoing fixture" in fixture.text(), "A completed blend must remain readable")
        val card = assertNotNull(fixture.mergedNodes().firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Outgoing fixture" } == true &&
                node.config.getOrNull(SemanticsActions.OnClick) != null
        })
        fixture.click(card.boundsInRoot.center)
        assertTrue(MixViewRequest.open, "A tap inside the popup must not dismiss through the scrim")
        fixture.click(Offset(4f, 4f))
        fixture.advance(500)
        assertFalse(MixViewRequest.open)
        assertFalse("Outgoing fixture" in fixture.text())
    }

    @Test fun windowBackDispatcherDismissesTheMixLayer() = inScene(open = true) { fixture ->
        assertTrue(PlayerBack.dispatch(), "The actual desktop back dispatcher must own the open overlay")
        fixture.advance(500)
        assertFalse(MixViewRequest.open)
        assertFalse("Outgoing fixture" in fixture.text())
    }

    @Test fun staleOpenRequestCannotInventAViewOrOpenALaterBlend() = inScene(view = null, open = true) { fixture ->
        assertFalse(MixViewRequest.open)
        assertFalse("Outgoing fixture" in fixture.text())
        fixture.view.value = view()
        fixture.advance(500)
        assertFalse(MixViewRequest.open)
        assertFalse("Outgoing fixture" in fixture.text(), "A later blend needs a new user request")
    }

    @Test fun loopClockWaveformsIdentifyTheirMixTimelineWithoutRelabelingSourceViews() {
        for (timeline in MixViewTimeline.entries) inScene(view = view(timeline), open = true) { fixture ->
            if (timeline == MixViewTimeline.MIX) {
                assertTrue("Mix timeline" in fixture.text(), "Loop-clock waveforms need an explicit timeline label")
            } else {
                assertFalse("Mix timeline" in fixture.text())
            }
        }
    }

    private fun view(timeline: MixViewTimeline = MixViewTimeline.SOURCE): MixTransitionView {
        fun deck(title: String) = MixDeck(title, "Fixture artist", null, 120.0, "A minor",
            180_000, -1.0, 5.0, floatArrayOf(.2f, .4f, .3f),
            floatArrayOf(.4f, .2f, .5f), floatArrayOf(.1f, .3f, .2f))
        return MixTransitionView(deck("Outgoing fixture"), deck("Incoming fixture"),
            0.0, 4.0, 0.0, 4.0, 8, floatArrayOf(0f, .5f, 1f),
            floatArrayOf(1f, .7f, 0f), floatArrayOf(0f, .7f, 1f), .5f, timeline)
    }

    private inner class Fixture(val scene: ImageComposeScene, val view: MutableStateFlow<MixTransitionView?>) {
        private var frameNanos = 0L
        fun advance(milliseconds: Long) {
            val target = frameNanos + milliseconds * 1_000_000
            while (frameNanos < target) {
                frameNanos = minOf(target, frameNanos + 16_666_667)
                scene.render(frameNanos).close()
            }
        }
        fun click(point: Offset) {
            val time = frameNanos / 1_000_000 + 1
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = time,
                type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = time + 20,
                type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
            advance(30)
        }
        fun mergedNodes() = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
        fun text() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString("\n") { it.text }
        fun mixingTarget() = mergedNodes().firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Mixing" } == true &&
                node.config.getOrNull(SemanticsActions.OnClick) != null
        }
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun inScene(view: MixTransitionView? = view(), open: Boolean = false, diagnostics: Boolean = false,
                        block: (Fixture) -> Unit) {
        val previousHost = runCatching { PlayerPlatform.host }.getOrNull()
        val previousOpen = MixViewRequest.open
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        val viewFlow = MutableStateFlow(view)
        val blend = MutableStateFlow<MixBlend?>(MixBlend(500f, 0L, false))
        val settings = Proxy.newProxyInstance(PlayerSettingsSource::class.java.classLoader,
            arrayOf(PlayerSettingsSource::class.java)) { _, method, _ ->
            when (method.name) {
                "getSmartMixView" -> viewFlow
                "getSmartMixProgress" -> MutableStateFlow(.4f)
                "getReduceDynamicBlur", "getReduceAnimation" -> MutableStateFlow(true)
                "getShowNerdStats" -> MutableStateFlow(diagnostics)
                "getSmartMixBlend" -> blend
                else -> error("Unexpected setting ${method.name}")
            }
        } as PlayerSettingsSource
        val host = Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, _ ->
            if (method.name == "getSettings") settings else error("Unexpected host read ${method.name}")
        } as PlayerHost
        PlayerPlatform.install(host)
        MixViewRequest.open = open
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val scene = ImageComposeScene(900, 800, Density(1f), coroutineContext = Dispatchers.Unconfined) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(360.dp).align(Alignment.Center)) {
                            PlayerScrubber({ .4f }, 180_000, false, true, null, onScrub = {}, onScrubFinished = {}) {
                                Text("Fixture quality", Modifier.align(Alignment.Center))
                            }
                        }
                        MixViewOverlay(HazeState())
                    }
                }
            }
        }
        try {
            val fixture = Fixture(scene, viewFlow)
            repeat(8) { fixture.advance(100) }
            block(fixture)
        } finally {
            scene.close()
            MixViewRequest.open = previousOpen
            previousHost?.let(PlayerPlatform::install)
            Locale.setDefault(previousLocale)
        }
    }
}
