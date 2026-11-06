package com.music.bitchord.desktop

import com.music.bitchord.playback.PeakLimiter
import kotlin.math.abs
import kotlin.math.max

/** Applies software volume, then catches summation/stretching overs, linking every channel. */
internal class DesktopOutputLimiter {
    private val limiter = PeakLimiter(ceiling = 1f)
    private var channels = 0

    fun configure(format: DesktopPcmFormat) {
        channels = format.channels
        limiter.configure(format.sampleRate)
    }

    fun reset() = limiter.reset()

    fun process(samples: FloatArray, count: Int, volume: Float = 1f) {
        if (channels <= 0) return
        require(count in 0..samples.size && count % channels == 0)
        var start = 0
        while (start < count) {
            val end = start + channels
            var peak = 0f
            for (index in start until end) {
                if (volume != 1f) samples[index] *= volume
                peak = max(peak, abs(samples[index]))
            }
            val gain = limiter.gainFor(peak)
            if (gain < 1f) for (index in start until end) samples[index] *= gain
            start = end
        }
    }
}
