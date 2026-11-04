package com.music.bitchord.desktop

import com.music.bitchord.data.settings.MixTransitionView
import com.music.bitchord.playback.smart.LoopMix
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Captured off the audio thread, before reading a recording or opening its playback decoder. */
internal class DesktopLoopMixRecording private constructor(
    val stream: DesktopStream,
    private val file: LocalFile?,
) {
    private data class LocalFile(val path: Path, val key: String?, val bytes: Long,
                                 val modified: FileTime, val created: FileTime)

    fun matches(other: DesktopLoopMixRecording?): Boolean = other != null &&
        sameLoopMixRecording(stream, other.stream) &&
        (!stream.isLocalFile || file != null && file == other.file)

    /** Filesystem checks belong to preparation/analysis workers, never the render/control thread. */
    fun isCurrent(): Boolean = !stream.isLocalFile || file == localFile(stream)

    companion object {
        fun capture(stream: DesktopStream): DesktopLoopMixRecording? {
            val file = if (stream.isLocalFile) localFile(stream) ?: return null else null
            return DesktopLoopMixRecording(stream, file)
        }

        private fun localFile(stream: DesktopStream): LocalFile? = runCatching {
            val path = (if (stream.url.startsWith("file:", ignoreCase = true)) Path.of(URI(stream.url))
                else Path.of(stream.url)).toRealPath()
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            if (!attributes.isRegularFile) null else LocalFile(path, attributes.fileKey()?.toString(),
                attributes.size(), attributes.lastModifiedTime(), attributes.creationTime())
        }.getOrNull()
    }
}

/** Recording identity is narrower than a catalogue match or equal advertised duration. */
internal fun sameLoopMixRecording(measured: DesktopStream?, playing: DesktopStream): Boolean {
    if (measured == null || measured.transport != playing.transport ||
        measured.sourceId != playing.sourceId) return false
    if (measured.url == playing.url && measured.headers == playing.headers) return true
    // Signed Google media URLs expire. These fields identify the same encoded content revision.
    // Do not infer a match across renditions, catalogue sources, redirects or missing tokens.
    if (measured.sourceId != "youtube") return false
    fun tokens(stream: DesktopStream): Map<String, String>? = runCatching {
        val uri = URI(stream.url)
        if (uri.scheme != "https" || uri.host?.endsWith(".googlevideo.com") != true) return null
        val query = uri.rawQuery?.split('&')?.mapNotNull {
            val split = it.indexOf('='); if (split < 1) null else it.substring(0, split) to it.substring(split + 1)
        }?.toMap() ?: return null
        val keys = listOf("id", "itag", "lmt", "clen", "dur")
        if (keys.any { query[it].isNullOrBlank() }) null else keys.associateWith { query.getValue(it) }
    }.getOrNull()
    val left = tokens(measured) ?: return false
    return left == tokens(playing)
}

/** A single cancelable native owner; the audio thread receives only fully prepared graphs. */
internal class DesktopLoopMixPreparation {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "BitChord-Loop preparation").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val queue = DesktopPreparationQueue<Long>(worker)
    private val selectionLock = Any()
    private var selectedKey: Long? = null

    fun select(key: Long?) = synchronized(selectionLock) {
        selectedKey = key
        queue.retain(listOfNotNull(key))
    }

    fun cancelSelected(key: Long) = synchronized(selectionLock) {
        if (selectedKey == key) {
            selectedKey = null
            queue.retain(emptyList())
        }
    }

    fun prepare(key: Long, plan: LoopMix.Plan, sampleRate: Int, outgoing: DesktopStream,
                incoming: DesktopStream, outgoingRecording: DesktopLoopMixRecording,
                incomingRecording: DesktopLoopMixRecording, ready: (DesktopLoopMixGraph) -> Unit) {
        queue.submit(key) { pass ->
            val graph = runCatching {
                check(outgoingRecording.isCurrent() && incomingRecording.isCurrent()) {
                    "local recording changed after measurement"
                }
                DesktopLoopMixGraph.build(plan, sampleRate, 1f, { pass.cancelled }) { side, from, to ->
                    decode(if (side == LoopMix.Side.OUT) outgoing else incoming, sampleRate, from, to, pass)
                }?.takeIf { graph ->
                    if (outgoingRecording.isCurrent() && incomingRecording.isCurrent()) true
                    else { graph.close(); false }
                }
            }.onFailure { if (!pass.cancelled) DesktopTrackLog.log("LoopMix preparation unavailable: ${it.message}") }.getOrNull()
            if (graph != null && !pass.publish { ready(graph) }) graph.close()
        }
    }

    fun retire(graph: DesktopLoopMixGraph) { worker.execute { graph.close() } }

    /** Optional chart work shares the graph worker and yields to a newly selected graph. */
    fun prepareWaves(key: Long, view: MixTransitionView, outgoing: DesktopLoopMixRecording?,
                     incoming: DesktopLoopMixRecording?, obsolete: () -> Boolean = { false },
                     ready: (MixTransitionView) -> Unit) {
        queue.submit(key) { pass ->
            val rate = 12_000
            fun read(recording: DesktopLoopMixRecording?, from: Double, to: Double): DesktopLoopMixGraph.Region? {
                if (recording == null || pass.cancelled || obsolete() || !recording.isCurrent()) return null
                return runCatching { decode(recording.stream, rate, from, to, pass) }.getOrNull()
                    ?.takeIf { recording.isCurrent() && !pass.cancelled && !obsolete() }
            }
            val out = read(outgoing, view.outgoing.waveStart, view.outgoing.waveEnd)
            val into = read(incoming, view.incoming.waveStart, view.incoming.waveEnd)
            val updated = DesktopMixView.withWaves(view, out, into, rate) { pass.cancelled || obsolete() } ?: return@submit
            pass.publish { ready(updated) }
        }
    }

    fun close() = queue.close()

    val isTerminated: Boolean get() = queue.isTerminated

    fun awaitTermination(timeoutNanos: Long): Boolean = queue.awaitTermination(timeoutNanos)

    private fun decode(stream: DesktopStream, rate: Int, from: Double, to: Double,
                       pass: DesktopPreparationQueue.Pass): DesktopLoopMixGraph.Region? {
        val startFrame = (from * rate).toLong().coerceAtLeast(0)
        val frames = ceil(to * rate).toLong() - startFrame
        // Each deck is bounded to 48 MB of stereo PCM; unsupported spans keep the ordinary fade.
        if (frames !in 1..6_000_000 || pass.cancelled) return null
        val decoder = DesktopAudioDecoder()
        val unbind = pass.onCancel(decoder::cancelPendingRead)
        try {
            if (pass.cancelled || decoder.open(stream.url, stream.headers,
                    requested = DesktopPcmFormat(rate, 2, 4, true), windowed = stream.windowedReads,
                    transport = stream.transport, preserveSourceSampleRate = false).isFailure) return null
            if (pass.cancelled || !decoder.seek((startFrame * 1_000_000.0 / rate).roundToLong())) return null
            val left = FloatArray(frames.toInt())
            val right = FloatArray(frames.toInt())
            var filled = 0
            while (filled < frames && !pass.cancelled) {
                val block = decoder.readSamples() ?: return null
                val observedUs = decoder.samplePositionUs ?: return null
                val expectedUs = (startFrame + filled) * 1_000_000.0 / rate
                // Reject unknown/late seeks and discontinuous timestamps rather than relabeling PCM.
                val toleranceUs = if (filled == 0) 1_000_000.0 / rate + 1
                    else decoder.timestampPrecisionUs + 1_000_000.0 / rate + 1
                if (kotlin.math.abs(observedUs - expectedUs) > toleranceUs) return null
                val count = minOf(decoder.sampleCount / 2, frames.toInt() - filled)
                if (count == 0) continue
                for (i in 0 until count) { left[filled + i] = block[i * 2]; right[filled + i] = block[i * 2 + 1] }
                filled += count
            }
            return if (pass.cancelled) null else DesktopLoopMixGraph.Region(left, right, startFrame.toDouble() / rate)
        } finally { unbind(); decoder.close() }
    }
}
