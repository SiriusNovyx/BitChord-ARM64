package com.music.bitchord.playback

import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.util.IdentityHashMap

/** Decoder events belong to a player instance and an event window, including prepared standby players. */
internal class PlayerAudioObservations {
    private class Entry(var format: Format? = null, var decoder: String? = null)
    private class State {
        val entries = linkedMapOf<String, Entry>()
        lateinit var listener: AnalyticsListener
        fun entry(id: String): Entry = entries.getOrPut(id) { Entry() }.also {
            while (entries.size > 4) entries.remove(entries.keys.first())
        }
    }
    private val players = IdentityHashMap<ExoPlayer, State>()

    @Synchronized fun attachTo(player: ExoPlayer) {
        if (players.containsKey(player)) return
        val state = State()
        players[player] = state
        state.listener = object : AnalyticsListener {
            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?) = synchronized(this@PlayerAudioObservations) {
                if (players[player] !== state) return@synchronized
                val id = eventTime.mediaId() ?: return@synchronized
                val entry = state.entry(id)
                entry.format = format
                if (decoderReuseEvaluation != null) {
                    if (decoderReuseEvaluation.result != DecoderReuseEvaluation.REUSE_RESULT_NO) {
                        entry.decoder = decoderReuseEvaluation.decoderName
                    } else if (entry.decoder == decoderReuseEvaluation.decoderName) {
                        entry.decoder = null
                    }
                }
            }

            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long) = synchronized(this@PlayerAudioObservations) {
                if (players[player] !== state) return@synchronized
                val id = eventTime.mediaId() ?: return@synchronized
                state.entry(id).decoder = decoderName
            }

            override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) =
                synchronized(this@PlayerAudioObservations) {
                    if (players[player] !== state) return@synchronized
                    state.entries.values.filter { it.decoder == decoderName }.forEach { it.decoder = null }
                }

            override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) =
                synchronized(this@PlayerAudioObservations) {
                    if (players[player] !== state) return@synchronized
                    state.entries.clear()
                }
        }
        player.addAnalyticsListener(state.listener)
    }

    @Synchronized fun formatFor(player: ExoPlayer, mediaId: String?): Format? = players[player]?.entries?.get(mediaId)?.format
    @Synchronized fun decoderFor(player: ExoPlayer, mediaId: String?): String? = players[player]?.entries?.get(mediaId)?.decoder

    @Synchronized fun forget(player: ExoPlayer) {
        val state = players.remove(player) ?: return
        player.removeAnalyticsListener(state.listener)
    }

    @Synchronized fun clear() { players.keys.toList().forEach(::forget) }

    private fun AnalyticsListener.EventTime.mediaId(): String? = timeline
        .takeIf { windowIndex >= 0 && windowIndex < it.windowCount }
        ?.getWindow(windowIndex, Timeline.Window())?.mediaItem?.mediaId?.takeIf { it.isNotBlank() }
}
