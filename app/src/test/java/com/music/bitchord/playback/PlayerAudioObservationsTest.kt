package com.music.bitchord.playback

import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import org.junit.Test
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame

class PlayerAudioObservationsTest {
    private class Deck {
        val listeners = mutableListOf<AnalyticsListener>()
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
            when (method.name) {
                "addAnalyticsListener" -> { listeners += args!![0] as AnalyticsListener; null }
                "removeAnalyticsListener" -> { listeners -= args!![0] as AnalyticsListener; null }
                else -> error("Unexpected player call: ${method.name}")
            }
        } as ExoPlayer
        fun input(id: String, format: Format, reuse: DecoderReuseEvaluation? = null) =
            listeners.toList().forEach { it.onAudioInputFormatChanged(event(id), format, reuse) }
        fun decoder(id: String, name: String) = listeners.toList().forEach { it.onAudioDecoderInitialized(event(id), name, 0, 0) }
    }

    @Test fun preparedStandbyRetainsItsInputAndDecoderBeforeItOwnsPlayback() {
        val observations = PlayerAudioObservations()
        val outgoing = Deck()
        val incoming = Deck()
        observations.attachTo(outgoing.player)
        observations.attachTo(incoming.player)
        val aac = Format.Builder().setSampleMimeType("audio/mp4a-latm").build()
        val joc = Format.Builder().setSampleMimeType("audio/eac3-joc").setCodecs("ec-3 JOC").build()
        outgoing.input("A", aac)
        outgoing.decoder("A", "aac-decoder")
        incoming.input("B", joc)
        incoming.decoder("B", "eac3-decoder")
        assertSame(joc, observations.formatFor(incoming.player, "B"))
        assertEquals("eac3-decoder", observations.decoderFor(incoming.player, "B"))
        assertSame(aac, observations.formatFor(outgoing.player, "A"))
        assertNull(observations.formatFor(outgoing.player, "B"))
    }

    @Test fun sameTrackOnAnotherPlayerCannotSupplyItsOldRendition() {
        val observations = PlayerAudioObservations()
        val old = Deck()
        val replacement = Deck()
        observations.attachTo(old.player)
        observations.attachTo(replacement.player)
        val stereo = Format.Builder().setSampleMimeType("audio/eac3").build()
        val joc = Format.Builder().setSampleMimeType("audio/eac3-joc").build()
        old.input("same-id", stereo)
        replacement.input("same-id", joc)
        old.decoder("same-id", "old-decoder")
        assertSame(joc, observations.formatFor(replacement.player, "same-id"))
        assertNull(observations.decoderFor(replacement.player, "same-id"))
        assertSame(stereo, observations.formatFor(old.player, "same-id"))
    }

    @Test fun lateOtherTrackEventsDoNotReplaceCurrentInput() {
        val observations = PlayerAudioObservations()
        val deck = Deck()
        observations.attachTo(deck.player)
        val current = Format.Builder().setSampleMimeType("audio/flac").build()
        deck.input("current", current)
        deck.decoder("current", "flac-decoder")
        deck.input("older", Format.Builder().setSampleMimeType("audio/mp4a-latm").build())
        deck.decoder("older", "aac-decoder")
        assertSame(current, observations.formatFor(deck.player, "current"))
        assertEquals("flac-decoder", observations.decoderFor(deck.player, "current"))
    }

    @Test fun disabledAudioAndForgottenPlayersCannotRetainOrReviveEvidence() {
        val observations = PlayerAudioObservations()
        val deck = Deck()
        observations.attachTo(deck.player)
        observations.attachTo(deck.player)
        assertEquals(1, deck.listeners.size)
        val callback = deck.listeners.single()
        deck.input("A", Format.Builder().setSampleMimeType("audio/flac").build())
        deck.decoder("A", "flac-decoder")
        callback.onAudioDisabled(event("A"), DecoderCounters())
        assertNull(observations.formatFor(deck.player, "A"))
        assertNull(observations.decoderFor(deck.player, "A"))
        observations.forget(deck.player)
        assertEquals(0, deck.listeners.size)
        callback.onAudioInputFormatChanged(event("A"), Format.Builder().setSampleMimeType("audio/flac").build(), null)
        assertNull(observations.formatFor(deck.player, "A"))
    }

    @Test fun historyIsBoundedAndDecoderReuseDoesNotRequireANewInitialization() {
        val observations = PlayerAudioObservations()
        val deck = Deck()
        observations.attachTo(deck.player)
        val format = Format.Builder().setSampleMimeType("audio/mp4a-latm").build()
        deck.input("first", format)
        deck.decoder("first", "aac-decoder")
        val reuse = DecoderReuseEvaluation("aac-decoder", format, format, DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION, 0)
        repeat(100) { deck.input("next-$it", format, reuse) }
        assertNull(observations.formatFor(deck.player, "first"))
        assertSame(format, observations.formatFor(deck.player, "next-99"))
        assertEquals("aac-decoder", observations.decoderFor(deck.player, "next-99"))
        observations.clear()
        assertEquals(0, deck.listeners.size)
        assertNull(observations.formatFor(deck.player, "next-99"))
    }

    @Test fun anotherTrackWithoutReuseEvidenceCannotInheritADecoderName() {
        val observations = PlayerAudioObservations()
        val deck = Deck()
        observations.attachTo(deck.player)
        val format = Format.Builder().setSampleMimeType("audio/mp4a-latm").build()
        deck.input("A", format)
        deck.decoder("A", "aac-decoder")
        deck.input("B", format)
        assertNull(observations.decoderFor(deck.player, "B"))
    }

    @Test fun explicitReuseOutranksALateOtherTrackInitialization() {
        val observations = PlayerAudioObservations()
        val deck = Deck()
        observations.attachTo(deck.player)
        val format = Format.Builder().setSampleMimeType("audio/flac").build()
        deck.input("current", format)
        deck.decoder("current", "flac-decoder")
        deck.decoder("older", "aac-decoder")
        val reuse = DecoderReuseEvaluation("flac-decoder", format, format, DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION, 0)
        deck.input("next", format, reuse)
        assertEquals("flac-decoder", observations.decoderFor(deck.player, "next"))
    }

    @Test fun rejectedReuseClearsTheOldDecoderForTheSameTrack() {
        val observations = PlayerAudioObservations()
        val deck = Deck()
        observations.attachTo(deck.player)
        val old = Format.Builder().setSampleMimeType("audio/mp4a-latm").build()
        val next = Format.Builder().setSampleMimeType("audio/flac").build()
        deck.input("same-id", old)
        deck.decoder("same-id", "aac-decoder")
        deck.input("same-id", next, DecoderReuseEvaluation("aac-decoder", old, next,
            DecoderReuseEvaluation.REUSE_RESULT_NO, DecoderReuseEvaluation.DISCARD_REASON_MIME_TYPE_CHANGED))
        assertSame(next, observations.formatFor(deck.player, "same-id"))
        assertNull(observations.decoderFor(deck.player, "same-id"))
    }

    @Test fun releasingAnOldDecoderDoesNotEraseTheReplacementObservation() {
        val observations = PlayerAudioObservations()
        val deck = Deck()
        observations.attachTo(deck.player)
        val format = Format.Builder().setSampleMimeType("audio/flac").build()
        deck.input("same-id", format)
        deck.decoder("same-id", "new-decoder")
        deck.listeners.single().onAudioDecoderReleased(event("same-id"), "old-decoder")
        assertSame(format, observations.formatFor(deck.player, "same-id"))
        assertEquals("new-decoder", observations.decoderFor(deck.player, "same-id"))
        deck.listeners.single().onAudioDecoderReleased(event("same-id"), "new-decoder")
        assertNull(observations.decoderFor(deck.player, "same-id"))
    }

    companion object {
        private fun event(id: String): AnalyticsListener.EventTime {
            val timeline = object : Timeline() {
                override fun getWindowCount() = 1
                override fun getPeriodCount() = 1
                override fun getWindow(index: Int, window: Window, projection: Long): Window = window.apply {
                    mediaItem = MediaItem.Builder().setMediaId(id).build()
                }
                override fun getPeriod(index: Int, period: Period, setIds: Boolean) = period
                override fun getIndexOfPeriod(uid: Any) = 0
                override fun getUidOfPeriod(index: Int): Any = id
            }
            return AnalyticsListener.EventTime(0, timeline, 0, null, 0, timeline, 0, null, 0, 0)
        }
    }
}
