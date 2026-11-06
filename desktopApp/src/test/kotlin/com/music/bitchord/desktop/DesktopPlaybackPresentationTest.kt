package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TrackAnalysisEligibility
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.data.settings.TrackAnalysisStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopPlaybackPresentationTest {
    private val playing = DesktopPlaybackState(
        song = Song("A", "Synthetic", "Artist", null),
        isPlaying = true,
        durationMs = 60_000,
        positionSampledAtNanos = 1,
    )

    private suspend fun exercise(active: Boolean, block: suspend Fixture.() -> Unit) = kotlinx.coroutines.coroutineScope {
        val fixture = Fixture(this, active)
        try { fixture.block() } finally { fixture.close() }
    }

    private inner class Fixture(scope: CoroutineScope, active: Boolean) {
        val source = MutableStateFlow(playing)
        val visible = MutableStateFlow(active)
        private val observed = Channel<DesktopPlaybackState>(Channel.UNLIMITED)
        private val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            desktopPlaybackPresentation(source, visible).collect { observed.send(it) }
        }
        suspend fun next(): DesktopPlaybackState = withTimeout(2_000) { observed.receive() }
        suspend fun noUpdate() { assertNull(withTimeoutOrNull(100) { observed.receive() }) }
        fun close() { job.cancel(); observed.close() }
    }

    @Test fun `hidden position ticks do not invalidate the presentation`() = runBlocking {
        exercise(false) {
            assertEquals(playing, next())
            source.value = playing.copy(positionMs = 2_000, positionSampledAtNanos = 10)
            noUpdate()
            source.value = playing.copy(positionMs = 5_000, positionSampledAtNanos = 20)
            noUpdate()
            assertEquals(5_000L, source.value.positionMs, "presentation filtering must not stop the engine clock")
        }
    }

    @Test fun `foreground still receives the complete measured position pair`() = runBlocking {
        exercise(true) {
            assertEquals(playing, next())
            for (position in listOf(1_000L, 2_000L)) {
                val updated = playing.copy(positionMs = position, positionSampledAtNanos = position * 1_000_000)
                source.value = updated
                assertEquals(updated, next())
            }
        }
    }

    @Test fun `restoring the window immediately publishes the latest held position`() = runBlocking {
        exercise(false) {
            assertEquals(playing, next())
            val latest = playing.copy(positionMs = 12_000, positionSampledAtNanos = 123_000_000)
            source.value = latest
            noUpdate()
            visible.value = true
            assertEquals(latest, next())
        }
    }

    @Test fun `background controls and analysis retain their live position and metadata`() = runBlocking {
        exercise(false) {
            assertEquals(playing, next())
            val paused = playing.copy(isPlaying = false, positionMs = 3_000, positionSampledAtNanos = 30)
            val seeking = playing.copy(positionMs = 20_000, positionSampledAtNanos = 40, seeks = 1, awaitingAudio = true)
            val resumed = seeking.copy(awaitingAudio = false)
            val nextTrack = resumed.copy(song = Song("B", "Next", "Artist", null), positionMs = 0, seeks = 2)
            val analysed = nextTrack.copy(smartAnalysis = SmartAnalysis(current = TrackAnalysisStatus(
                eligibility = TrackAnalysisEligibility.ELIGIBLE,
                state = TrackAnalysisState.ANALYSED,
            )))
            for (updated in listOf(paused, seeking, resumed, nextTrack, analysed, analysed.copy(error = "Synthetic error"))) {
                source.value = updated
                assertEquals(updated, next())
            }
        }
    }
}
