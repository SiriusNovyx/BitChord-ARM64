package com.music.bitchord.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.music.bitchord.data.AudioDiagnostics
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TrackAnalysisEligibility
import com.music.bitchord.data.settings.TrackAnalysisStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class AudioDiagnosticsRenderingTest {
    @Test fun statsTargetFitsTheRealScrubberAtNormalAndLargeFontSizes() {
        for (scale in listOf(1f, 2f)) inScene(scale) { scene ->
            val target = targets(scene).single()
            assertTrue(target.boundsInRoot.height >= 48f,
                "A timestamp-sized box must not clip the diagnostic control or shrink its touch target")
            assertTrue(target.boundsInRoot.width >= 48f)
            assertTrue(target.boundsInRoot.width <= 192f,
                "Only the compact control may be clickable, not the full timestamp row: ${target.boundsInRoot}")
        }
    }

    @Test fun emptyTimestampSpaceDoesNotOpenDetails() = inScene(1f) { scene ->
        val target = targets(scene).single().boundsInRoot
        val point = Offset(60f, target.center.y)
        scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = 100,
            type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = 120,
            type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
        scene.render(500_000_000).close()
        assertFalse("Audio diagnostics" in text(scene), "Space outside the compact target must stay inert")
    }

    @Test fun openingDetailsRetainsActualStreamMeasurementsAndSource() = inScene(1f) { scene ->
        openDetails(scene)
        val text = text(scene)
        for (required in listOf("2304 kbps", "96.0 kHz", "24", "Fixture source", "Speakers", "Not confirmed")) {
            assertTrue(required in text, "Missing measured detail: $required; $text")
        }
    }

    @Test fun anotherTracksSnapshotCannotDescribeTheSelectedTrack() = inScene(1f, "older-track") { scene ->
        openDetails(scene)
        val text = text(scene)
        assertFalse("Fixture source" in text, "Previous track telemetry must not be attributed to this song")
        assertFalse("2304 kbps" in text)
        assertFalse("FLAC" in text, "Old decoder input must not label a newly selected track")
    }

    @Test fun aMissingCodecDoesNotHideTheObservedSampleRateInline() = inScene(1f, codec = null) { scene ->
        assertTrue("96.0 kHz" in text(scene), "Keep independently observed fields when the encoding is unknown")
    }

    @Test fun unsupportedAnalysisIsSeparateAndLabelsBothQueuePositions() = inScene(1f) { scene ->
        openDetails(scene)
        val text = text(scene)
        assertTrue("Current track:" in text)
        assertTrue("Up next:" in text)
        assertTrue("Automatic transitions: Off" in text)
        assertFalse("unsupported source / unsupported source" in text)
        assertTrue("96.0 kHz" in text, "Unsupported analysis cannot hide audio observations")
    }

    @Test fun detailedAutoMixEvidenceAppearsOnlyInsideAudioDetails() = inScene(1f,
        automixDetail = "LoopMix fixture: measured instrumental windows; shared device clock", smartEnabled = true) { scene ->
        assertFalse("LoopMix fixture" in text(scene), "Detailed diagnostics must not cover the player artwork")
        openDetails(scene)
        assertFalse("LoopMix fixture" in text(scene), "Detailed mix evidence stays behind the secondary expansion")
        val more = all(scene).firstOrNull { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                nodes(node).any { child ->
                    child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "More diagnostics" } == true
                }
        }
        assertNotNull(more, "The detailed evidence needs an accessible expansion control")
        assertTrue(more.config[SemanticsActions.OnClick].action!!.invoke())
        scene.render(640_000_000).close()
        scene.render(1_000_000_000).close()
        assertTrue("LoopMix fixture: measured instrumental windows; shared device clock" in text(scene))
    }

    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun all(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
    private fun targets(scene: ImageComposeScene) = all(scene)
        .filter { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Audio diagnostics" }
    private fun text(scene: ImageComposeScene) = all(scene)
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString("\n") { it.text }
    private fun openDetails(scene: ImageComposeScene) {
        val point = targets(scene).single().boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press, Offset(point.x, point.y), timeMillis = 100,
            type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, Offset(point.x, point.y), timeMillis = 120,
            type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
        scene.render(140_000_000).close()
        scene.render(500_000_000).close()
        assertTrue("Audio diagnostics" in text(scene), "The real pointer click must open the dialog")
    }

    private fun inScene(scale: Float, observedMediaId: String = "fixture", codec: String? = "audio/flac",
                        automixDetail: String? = null, smartEnabled: Boolean = false, block: (ImageComposeScene) -> Unit) {
        val previousHost = runCatching { PlayerPlatform.host }.getOrNull()
        val previous = NerdStats.current.value
        val settings = Proxy.newProxyInstance(PlayerSettingsSource::class.java.classLoader, arrayOf(PlayerSettingsSource::class.java)) { _, method, _ ->
            when (method.name) {
                "getSmartAnalysis" -> MutableStateFlow(SmartAnalysis(
                    TrackAnalysisStatus(TrackAnalysisEligibility.SOURCE_POLICY),
                    TrackAnalysisStatus(TrackAnalysisEligibility.SOURCE_POLICY)))
                "getSmartFadeEnabled" -> MutableStateFlow(smartEnabled)
                "getAutomixDetail" -> MutableStateFlow(automixDetail)
                "getSmartMixBlend" -> MutableStateFlow<Any?>(null)
                "getReduceAnimation", "getShowNerdStats" -> MutableStateFlow(true)
                else -> error("Unexpected setting: ${method.name}")
            }
        } as PlayerSettingsSource
        val host = Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, _ ->
            if (method.name == "getSettings") settings else error("Unexpected host read ${method.name}")
        } as PlayerHost
        PlayerPlatform.install(host)
        NerdStats.current.value = NerdStats.Snapshot(codec, 2304, 96_000, 2, 24,
            sourceName = "Fixture source", mediaId = observedMediaId,
            audioDiagnostics = AudioDiagnostics(preferenceEnabled = true, observedCodec = codec, outputRoute = "Speakers", outputChannels = 2))
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val song = Song("fixture", "Fixture", "Artist", null)
        val scene = ImageComposeScene(320, 700, Density(1f, scale), coroutineContext = Dispatchers.Unconfined) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().padding(30.dp), contentAlignment = Alignment.Center) {
                        PlayerScrubber({ .25f }, 240_000, false, false, null, onScrub = {}, onScrubFinished = {}) {
                            CompactAudioDiagnostics(song, Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
        }
        try { scene.render(0).close(); block(scene) } finally {
            scene.close()
            NerdStats.current.value = previous
            previousHost?.let(PlayerPlatform::install)
        }
    }
}
