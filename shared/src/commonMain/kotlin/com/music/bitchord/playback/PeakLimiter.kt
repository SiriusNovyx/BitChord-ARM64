package com.music.bitchord.playback

import kotlin.math.exp

/**
 * The catch under the equaliser's partial make-up — see [EqCurve.preampDb].
 *
 * The curve only takes back half of its own boost, so the odd peak that lands
 * exactly where the boost is can still run past full scale. This turns those
 * down for a moment instead of letting the sink square them off.
 *
 * Instant attack, a smooth release, linked across channels so a peak on one
 * side never pulls the stereo image over, and no lookahead: the same shape as
 * the normalization limiter in Android's `LoudnessProcessor`, for the same
 * reason — it is a catcher for overs, not a mastering limiter, and most of the
 * time it does nothing at all.
 *
 * The default ceiling retains EQ headroom; final output can use full scale after software volume.
 * Audio thread only. Call [gainFor] once per frame with that frame's loudest
 * channel, and multiply every channel of the frame by what it returns.
 */
class PeakLimiter(private val ceiling: Float = CEILING) {

    init { require(ceiling.isFinite() && ceiling > 0f && ceiling <= 1f) }

    private var releaseCoef = 1f
    private var reduction = 1f

    fun configure(sampleRate: Int) {
        releaseCoef = if (sampleRate <= 0) {
            1f
        } else {
            (1.0 - exp(-1.0 / (RELEASE_SECONDS * sampleRate))).toFloat()
        }
        reduction = 1f
    }

    /** Lets go at once, for a seek or a bypass: there is nothing left to be continuous with. */
    fun reset() {
        reduction = 1f
    }

    /** What to multiply a frame by, given the magnitude of its loudest channel. */
    fun gainFor(peak: Float): Float {
        val wanted = if (peak > ceiling) ceiling / peak else 1f
        reduction = if (wanted < reduction) wanted else reduction + (wanted - reduction) * releaseCoef
        return reduction
    }

    companion object {
        /** Just under full scale, matching `LoudnessProcessor`, so float and 16-bit round the same way. */
        const val CEILING = 0.985f

        /**
         * How quickly it lets go after a peak. A little slower than the
         * normalization limiter's: an EQ boost is usually in the bass, and a
         * release that moves within a bass cycle is heard as grit.
         */
        private const val RELEASE_SECONDS = 0.1
    }
}
