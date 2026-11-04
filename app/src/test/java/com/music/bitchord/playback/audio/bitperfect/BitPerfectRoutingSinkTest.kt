package com.music.bitchord.playback.audio.bitperfect

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitPerfectRoutingSinkTest {
    @Test
    fun failedDirectWriteWaitsForAndroidToReclaimTheDac() {
        val outputs = object : BitPerfectOutputs {
            var pendingNormal = false
            val transport = BitPerfectAudioSinkTest.FakeTransport(44100, 2, 24).apply { failOnWrite = true }
            override fun canOpen(sampleRate: Int, channels: Int, sourceBits: Int, requester: Any) = true
            override fun open(sampleRate: Int, channels: Int, sourceBits: Int, requester: Any) = transport
            override fun release(requester: Any) { pendingNormal = true }
            override fun markFailed(sampleRate: Int, channels: Int, sourceBits: Int, reason: String) = Unit
            override fun publish(status: BitPerfectOutput.Status) = Unit
            override fun publishIdle() = Unit
            override fun normalOutputPending() = pendingNormal
        }
        var normalWrites = 0
        val normal = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "hasPendingData" -> false
                "handleBuffer" -> {
                    normalWrites++
                    (args!![0] as ByteBuffer).apply { position(limit()) }
                    true
                }
                else -> null
            }
        } as AudioSink
        val sink = BitPerfectRoutingSink(normal, BitPerfectAudioSink(outputs))
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT).setSampleRate(44100).setChannelCount(2).build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
        val buffer = ByteBuffer.allocateDirect(4)

        assertFalse(sink.handleBuffer(buffer, 0, 1))
        assertEquals(0, normalWrites)
        assertFalse(sink.handleBuffer(buffer, 0, 1))
        assertEquals(0, normalWrites)

        outputs.pendingNormal = false
        assertTrue(sink.handleBuffer(buffer, 0, 1))
        assertEquals(1, normalWrites)
        assertTrue(outputs.transport.released)
    }
}
