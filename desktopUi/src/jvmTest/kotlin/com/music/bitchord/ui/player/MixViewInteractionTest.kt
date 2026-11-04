package com.music.bitchord.ui.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.music.bitchord.data.settings.MixBlend
import com.music.bitchord.data.settings.MixDeck
import com.music.bitchord.data.settings.MixTransitionView
import com.music.bitchord.data.settings.MixViewTimeline
import dev.chrisbanes.haze.HazeState
import java.lang.reflect.Proxy
import java.util.Locale
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.Before
import org.junit.After
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class MixViewInteractionTest {
    private var previousLocale = Locale.getDefault()
    @Before fun useEnglishResources() { previousLocale = Locale.getDefault(); Locale.setDefault(Locale.ENGLISH) }
    @After fun restoreResources() { Locale.setDefault(previousLocale); MixViewRequest.open = false }
    @Test fun mixingLabelOpensTheMixView() {
        val pulse = MixPulse { MixBlend(500f, 0L, false) }
        MixViewRequest.open = false
        scene(420, 200) { MixingLabel(pulse) }.use { test ->
            test.render()
            val label = assertNotNull(test.nodes().firstOrNull { it.hasText("Mixing") })
            val click = assertNotNull(label.config.getOrNull(SemanticsActions.OnClick),
                "The native Mixing label must expose its existing mix-view action")
            assertTrue(click.action!!.invoke())
            assertTrue(MixViewRequest.open)
        }
        MixViewRequest.open = false
    }

    @Test fun mixClockChartsIdentifyTheirTimelineAndSourceChartsKeepTheirLayout() {
        for (timeline in MixViewTimeline.entries) {
            scene(420, 700) { MixViewContent(view(timeline), mutableStateOf(.4f)) }.use { test ->
                test.render()
                assertTrue(test.nodes().any { it.hasText("Outgoing track") })
                assertTrue(test.nodes().any { it.hasText("Incoming track") })
                assertEquals(timeline == MixViewTimeline.MIX,
                    test.nodes().any { it.hasText("Mix timeline") }, "Clock coordinates must be identified")
                test.capture("mix-content-${timeline.name.lowercase()}")
            }
        }
    }

    @Test fun openedMixViewHoldsTheCompletedBlendAndEscapeDismissesIt() {
        withHost { current, _ ->
            current.value = view(MixViewTimeline.MIX)
            MixViewRequest.open = true
            scene(440, 720) { MixViewOverlay(HazeState()) }.use { test ->
                test.render()
                assertTrue(test.nodes().any { it.hasText("Outgoing track") })
                current.value = null
                test.render()
                assertTrue(test.nodes().any { it.hasText("Incoming track") },
                    "Finishing a blend must not remove the open chart under the reader")
                test.capture("mix-overlay-held")
                assertTrue(PlayerBack.dispatch(), "The active mix overlay must own the native Escape action")
                assertFalse(MixViewRequest.open)
                test.render()
                assertFalse(PlayerBack.dispatch(), "A dismissed overlay must release native back handling")
            }
        }
    }

    @Test fun requestWithoutAnyBlendDoesNotLeaveAnInvisibleModalLayer() {
        withHost { _, _ ->
            MixViewRequest.open = true
            scene(440, 720) { MixViewOverlay(HazeState()) }.use { test ->
                test.render()
                assertFalse(MixViewRequest.open)
                assertFalse(PlayerBack.dispatch())
                assertFalse(test.nodes().any { it.hasText("Outgoing track") })
            }
        }
    }

    @Test fun hiddenPopupDoesNotObserveProgressAndOpenPopupRendersUpdates() {
        withHost { _, progress ->
            val visible = mutableStateOf(false)
            val view = view(MixViewTimeline.MIX)
            scene(440, 720) {
                MixViewPopup(view, visible.value, live = true, hazeState = HazeState(), onDismiss = {})
            }.use { test ->
                test.render()
                assertEquals(0, progress.subscriptionCount.value,
                    "A retained closed popup must not subscribe to playback's per-block progress")
                visible.value = true
                test.render()
                assertEquals(1, progress.subscriptionCount.value)
                val before = test.imageBytes()
                progress.value = .8f
                test.render()
                assertFalse(before.contentEquals(test.imageBytes()), "Visible progress must update the actual chart")
                visible.value = false
                test.render()
                assertEquals(0, progress.subscriptionCount.value)
                progress.value = .1f
                test.render()
                assertEquals(0, progress.subscriptionCount.value)
            }
        }
    }

    @Test fun closingProgressKeepsItsLastObservedPosition() {
        withHost { _, progress ->
            val visible = mutableStateOf(true)
            lateinit var observed: State<Float>
            scene(200, 100) { observed = rememberVisibleMixProgress(visible.value) }.use { test ->
                test.render()
                progress.value = .72f
                test.render()
                assertEquals(.72f, observed.value)
                visible.value = false
                test.render()
                assertEquals(0, progress.subscriptionCount.value)
                progress.value = .17f
                test.render()
                assertEquals(.72f, observed.value,
                    "Exit must hold the position already shown rather than follow a hidden progress flow")
            }
        }
    }

    private fun view(timeline: MixViewTimeline): MixTransitionView {
        fun deck(title: String) = MixDeck(title, "Artist", null, 120.0, "C major", 90_000,
            0.0, 20.0, FloatArray(64) { .2f + (it % 7) * .07f },
            FloatArray(64) { .15f + (it % 5) * .06f }, FloatArray(64) { .1f + (it % 3) * .05f })
        return MixTransitionView(deck("Outgoing track"), deck("Incoming track"),
            2.0, 18.0, 2.0, 18.0, 32, FloatArray(33) { it / 32f },
            FloatArray(33) { 1f - it / 32f }, FloatArray(33) { it / 32f }, .5f, timeline)
    }

    private inline fun withHost(block: (MutableStateFlow<MixTransitionView?>, MutableStateFlow<Float>) -> Unit) {
        val installed = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previous = installed.get(null)
        val current = MutableStateFlow<MixTransitionView?>(null)
        val progress = MutableStateFlow(.4f)
        val reduceBlur = MutableStateFlow(true)
        val settings = Proxy.newProxyInstance(PlayerSettingsSource::class.java.classLoader,
            arrayOf(PlayerSettingsSource::class.java)) { proxy, method, args ->
            when (method.name) {
                "getSmartMixView" -> current
                "getSmartMixProgress" -> progress
                "getReduceDynamicBlur" -> reduceBlur
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "MixView test settings"
                else -> error("Unexpected setting read: ${method.name}")
            }
        } as PlayerSettingsSource
        val host = Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) {
                _, method, _ ->
            when (method.name) {
                "getSettings" -> settings
                else -> error("Unexpected host read: ${method.name}")
            }
        } as PlayerHost
        try {
            PlayerPlatform.install(host)
            block(current, progress)
        } finally {
            MixViewRequest.open = false
            installed.set(null, previous)
        }
    }

    private fun scene(width: Int, height: Int, content: @Composable () -> Unit): TestScene {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        return TestScene(ImageComposeScene(width, height, Density(1f), coroutineContext = Dispatchers.Unconfined) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { MaterialTheme { content() } }
        })
    }

    private class TestScene(private val scene: ImageComposeScene) : AutoCloseable {
        private var frame = 0L
        fun render() { repeat(24) { scene.render(frame++ * 16_666_667L).close() } }
        fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
        fun imageBytes(): ByteArray = scene.render(frame++ * 16_666_667L).use { image ->
            image.encodeToData()!!.use { it.bytes }
        }
        fun capture(name: String) {
            val directory = System.getProperty("bitchord.testArtifacts") ?: return
            val target = Path.of(directory)
            Files.createDirectories(target)
            scene.render(frame++ * 16_666_667L).use { image ->
                image.encodeToData()?.use { Files.write(target.resolve("$name.png"), it.bytes) }
            }
        }
        override fun close() = scene.close()
    }

    companion object {
        private fun SemanticsNode.hasText(text: String) =
            config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    }
}
