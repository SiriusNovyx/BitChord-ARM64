package com.music.bitchord.desktop

/**
 * Keeps a deck's pitch-preserving stretcher and its surplus output together.
 *
 * A sped-up decoder does not produce exactly one output block for every input block. The FIFO lets
 * the mixer request exactly the outgoing block size without allocating or dropping the excess,
 * and survives promotion so the incoming song continues seamlessly after the handoff.
 */
internal class DesktopTempoBuffer(channels: Int, sampleRate: Int) {
    private val processor = DesktopAudioSpeed(channels, sampleRate)
    private var queued = FloatArray(0)
    private var queuedStart = 0
    private var queuedCount = 0
    private var output = FloatArray(0)
    private class RateSpan(var samples: Int, val rate: Double)
    private val rates = java.util.ArrayDeque<RateSpan>()

    /** Weighted source-time rate of the samples returned by the last [take]. */
    var outputRate: Double = 1.0
        private set

    var speed: Float
        get() = processor.speed
        set(value) {
            processor.speed = value.coerceIn(1f, 1.1f)
        }

    val available: Int get() = queuedCount

    fun push(samples: FloatArray, count: Int) {
        if (count <= 0) return
        val rendered = processor.process(samples, count)
        append(rendered, processor.outputCount, processor.outputRate.toDouble())
    }

    /**
     * Queues whatever the stretcher is still holding, unstretched, so the deck can go back to
     * reading its decoder directly once [available] runs out — seamlessly, because the held audio
     * is exactly what comes before the decoder's next block.
     */
    fun finish() {
        val rest = processor.drain()
        append(rest, processor.outputCount, 1.0)
    }

    /** Returns up to [wanted] queued samples. The returned array is reused. */
    fun take(wanted: Int): FloatArray {
        val count = minOf(wanted, queuedCount)
        if (output.size < count) output = FloatArray(count)
        if (count > 0) System.arraycopy(queued, queuedStart, output, 0, count)
        var left = count
        var sourceSamples = 0.0
        while (left > 0) {
            val span = rates.first
            val consumed = minOf(left, span.samples)
            sourceSamples += consumed * span.rate
            span.samples -= consumed
            left -= consumed
            if (span.samples == 0) rates.removeFirst()
        }
        outputRate = if (count > 0) sourceSamples / count else 1.0
        queuedStart += count
        queuedCount -= count
        if (queuedCount == 0) queuedStart = 0
        outputCount = count
        return output
    }

    var outputCount: Int = 0
        private set

    private fun append(samples: FloatArray, count: Int, rate: Double) {
        if (count <= 0) return
        val needed = queuedCount + count
        if (queuedStart + needed > queued.size) {
            val grown = FloatArray(maxOf(needed, queued.size * 2, 4_096))
            if (queuedCount > 0) System.arraycopy(queued, queuedStart, grown, 0, queuedCount)
            queued = grown
            queuedStart = 0
        }
        System.arraycopy(samples, 0, queued, queuedStart + queuedCount, count)
        queuedCount += count
        val previous = rates.peekLast()
        if (previous?.rate == rate) previous.samples += count
        else rates.addLast(RateSpan(count, rate))
    }
}
