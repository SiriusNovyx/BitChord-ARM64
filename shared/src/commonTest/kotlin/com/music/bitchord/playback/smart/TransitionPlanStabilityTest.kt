package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransitionPlanStabilityTest {
    private fun analyzed(id: String, duration: Double, bpm: Double): TrackAnalysis {
        val energy = List((duration * 10).toInt() + 1) { EnergySample(it / 10.0, 1.0) }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY, trackId = id, duration = duration,
            bpm = bpm, beatInterval = 60.0 / bpm, beatConfidence = .5,
            contentEndTime = duration, audibleStartTime = 0.0,
            key = "C minor", keyConfidence = .1, energyCurve = energy,
            vocalActivityMask = energy.map { 1.0 }, vocalProbability = .4,
        )
    }

    private fun plan(outgoing: TrackAnalysis, incoming: TrackAnalysis, at: Double) = planTransition(
        outgoing, incoming, TransitionTrackInfo(outgoing.trackId, 214000L),
        TransitionTrackInfo(incoming.trackId, 102000L), currentTime = at, duration = 214.0,
        mode = CrossfadeMode.SMART, advanced = true, mixWindow = true,
    )

    private fun laterExitPair(): Pair<TrackAnalysis, TrackAnalysis> {
        val outgoing = analyzed("out", 214.0, 48.0).let { source -> source.copy(
            mixOutCandidates = listOf(
                MixCandidate(212.14, 1.0, "outro_start"), MixCandidate(214.0, .5, "content_end"),
            ),
            vocalActivityMask = source.energyCurve.map { if (it.time >= 207.0 && it.time < 209.0) 1.0 else 0.0 },
        ) }
        return outgoing to analyzed("in", 102.0, 175.0)
    }

    private fun earlierExitPair(): Pair<TrackAnalysis, TrackAnalysis> {
        val outgoing = analyzed("out", 214.0, 48.0).let { source -> source.copy(
            mixOutCandidates = listOf(
                MixCandidate(214.0, 1.0, "content_end"), MixCandidate(212.14, .1, "outro_start"),
            ),
            vocalActivityMask = source.energyCurve.map { if (it.time >= 209.0 && it.time < 211.0) 1.0 else 0.0 },
        ) }
        val incoming = analyzed("in", 102.0, 175.0).let { source -> source.copy(
            vocalActivityMask = source.energyCurve.map { if (it.time < 2.0) 1.0 else 0.0 },
        ) }
        return outgoing to incoming
    }

    @Test fun laterVocalSafeExitSurvivesTheRejectedPrimaryStart() {
        val (outgoing, incoming) = laterExitPair()
        val selected = plan(outgoing, incoming, 200.0)
        assertEquals(214.0, selected.transitionEnd, "fixture must select the later vocal-safe exit")
        val afterRejectedStart = plan(outgoing, incoming, 207.15)
        assertEquals(selected.transitionStart, afterRejectedStart.transitionStart, 1e-9)
        assertEquals(selected.transitionEnd, afterRejectedStart.transitionEnd, 1e-9)
        assertEquals(selected.vocalOverlap, afterRejectedStart.vocalOverlap, 1e-9)
        assertFalse(afterRejectedStart.shouldStart)
        val atSelectedStart = plan(outgoing, incoming, selected.transitionStart)
        assertEquals(selected.transitionStart, atSelectedStart.transitionStart, 1e-9)
        assertTrue(atSelectedStart.shouldStart)
    }

    @Test fun earlierVocalSafeExitRemainsSelectedWhileItsWindowIsActive() {
        val (outgoing, incoming) = earlierExitPair()
        val selected = plan(outgoing, incoming, 200.0)
        assertEquals(212.14, selected.transitionEnd, 1e-9, "fixture must select the earlier vocal-safe exit")
        for (at in listOf(selected.transitionStart, selected.transitionStart + .01, selected.transitionEnd - .1)) {
            val active = plan(outgoing, incoming, at)
            assertEquals(selected.transitionStart, active.transitionStart, 1e-9, "selection at $at")
            assertEquals(selected.transitionEnd, active.transitionEnd, 1e-9)
            assertTrue(active.shouldStart)
        }
    }

    @Test fun expiredVocalSafeExitIsNotReusedForLatePreparation() {
        val (outgoing, incoming) = earlierExitPair()
        val selected = plan(outgoing, incoming, 200.0)
        val late = plan(outgoing, incoming, selected.transitionEnd + .1)
        assertTrue(late.transitionEnd > selected.transitionEnd + .1)
        assertEquals(214.0, late.transitionEnd, 1e-9)
    }
}
