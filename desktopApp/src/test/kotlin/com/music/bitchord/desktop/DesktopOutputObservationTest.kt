package com.music.bitchord.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesktopOutputObservationTest {
    @Test fun completionRevisionRefreshesTheActualSharedPipelineBinding() {
        var playback by mutableStateOf(DesktopPlaybackState(isPlaying = true))
        var actual = DesktopAudioPipeline(outputBytesPerSample = 2)
        val observed = mutableListOf<DesktopAudioPipeline>()
        val scene = ImageComposeScene(200, 100, Density(1f), coroutineContext = Dispatchers.Unconfined) {
            ObserveDesktopOutput(Unit, playback, snapshot = { actual }, publish = { observed += it })
        }
        try {
            repeat(3) { scene.render(it * 16_666_667L).close() }
            assertEquals(2, observed.last().outputBytesPerSample)
            actual = actual.copy(outputBytesPerSample = 4, outputIsFloat = true)
            playback = playback.copy(outputRevision = playback.outputRevision + 1)
            repeat(3) { scene.render((it + 3) * 16_666_667L).close() }
            assertEquals(actual, observed.last(), "same-track output completion must refresh the shared summary")
            assertEquals(2, observed.size, "no polling or repeated publication is needed")
        } finally { scene.close() }
    }

    @Test fun unavailablePreferredEndpointUsesDefaultWithoutLosingItsStoredSelection() {
        val preferred = "usb:disconnected"
        assertEquals(DesktopAudioDevices.SYSTEM_DEFAULT,
            activeDesktopOutputId(preferred, listOf(DesktopAudioDevices.SYSTEM_DEFAULT, "speakers")))
        assertEquals("usb:disconnected", preferred)
        assertEquals(preferred,
            activeDesktopOutputId(preferred, listOf(DesktopAudioDevices.SYSTEM_DEFAULT, preferred)))
    }

    @Test fun matchingClientRatesAndDspDoNotClaimBitPerfectOutput() {
        val text = renderPipeline(DesktopAudioPipeline(decodedSampleRateHz = 48_000,
            outputSampleRateHz = 48_000, equalizerEnabled = true))
        assertFalse(text.contains(DesktopStrings["d_bit_perfect", "Bit-perfect · no conversion"]))
        assertTrue(text.contains(DesktopStrings["pipeline_no_app_resampling", "No sample-rate conversion in app"]))
    }

    @Test fun unknownRatesAndBackendAreNotReportedAsKnown() {
        val text = renderPipeline(DesktopAudioPipeline())
        assertFalse(text.contains(DesktopStrings["d_bit_perfect", "Bit-perfect · no conversion"]))
        assertFalse(text.contains("javax.sound"))
        assertTrue(text.contains(DesktopStrings["pipeline_unknown", "Unknown"]))
    }

    @Test fun dialogUsesTheObservedBackendAndRateConversion() {
        val text = renderPipeline(DesktopAudioPipeline(decodedSampleRateHz = 48_000,
            outputSampleRateHz = 44_100, outputApi = "WASAPI shared"))
        assertTrue(text.contains("WASAPI shared"))
        assertFalse(text.contains("javax.sound"))
        assertTrue(text.any { it.contains("48.0 kHz") && it.contains("44.1 kHz") })
    }

    private fun renderPipeline(pipeline: DesktopAudioPipeline): List<String> {
        val scene = ImageComposeScene(800, 1200, Density(1f), coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { DesktopAudioPipelineDialog(null, null, pipeline, {}) }
        }
        return try {
            repeat(3) { scene.render(it * 16_666_667L).close() }
            scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
        } finally { scene.close() }
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
