package com.music.bitchord.desktop

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopOutputLimiterTest {
    private val format = DesktopPcmFormat(48_000, 2, 4, isFloat = true)

    @Test fun `ordinary full scale samples are bit exact without prior overs`() {
        val limiter = DesktopOutputLimiter().apply { configure(format) }
        val input = floatArrayOf(0f, -0f, .1f, -.1f, .999f, -.999f, 1f, -1f)
        val original = input.map(Float::toRawBits)
        repeat(100) { limiter.process(input, input.size) }
        assertEquals(original, input.map(Float::toRawBits))
    }

    @Test fun `one overrange channel applies the same gain to its partners`() {
        val limiter = DesktopOutputLimiter().apply { configure(format.copy(channels = 6)) }
        val input = floatArrayOf(2f, 1f, -.5f, .25f, -.125f, 0f)
        limiter.process(input, input.size)
        assertContentEquals(floatArrayOf(1f, .5f, -.25f, .125f, -.0625f, 0f), input)
    }

    @Test fun `release spans blocks and approaches unity in sample time`() {
        val limiter = DesktopOutputLimiter().apply { configure(format) }
        limiter.process(floatArrayOf(2f, -2f), 2)
        val following = FloatArray(48_000 * 2) { .5f }
        limiter.process(following, following.size)
        assertTrue(following.first() in .25f.. .251f)
        assertTrue(following.last() in .499f.. .5f)
        assertTrue(following.all { it <= .5f })
    }

    @Test fun `different block sizes produce the same interleaved output`() {
        val all = DesktopOutputLimiter().apply { configure(format) }
        val chunks = DesktopOutputLimiter().apply { configure(format) }
        val first = FloatArray(20_000) { if (it % 17 == 0) 1.4f else -.4f }
        val second = first.copyOf()
        all.process(first, first.size)
        var at = 0
        while (at < second.size) {
            val end = minOf(at + 62, second.size)
            val part = second.copyOfRange(at, end)
            chunks.process(part, part.size)
            part.copyInto(second, at)
            at = end
        }
        assertContentEquals(first, second)
        assertTrue(first.all { abs(it) <= 1.00000012f })
    }

    @Test fun `reset drops gain reduction at a real discontinuity`() {
        val limiter = DesktopOutputLimiter().apply { configure(format) }
        limiter.process(floatArrayOf(2f, -2f), 2)
        limiter.reset()
        val next = floatArrayOf(.5f, -.5f)
        limiter.process(next, next.size)
        assertContentEquals(floatArrayOf(.5f, -.5f), next)
    }

    @Test fun `output reconfiguration resets gain and links the new channel layout`() {
        val limiter = DesktopOutputLimiter().apply { configure(format) }
        limiter.process(floatArrayOf(2f, -2f), 2)
        limiter.configure(format.copy(sampleRate = 96_000, channels = 1))
        val next = floatArrayOf(.99f, -.99f)
        limiter.process(next, next.size)
        assertContentEquals(floatArrayOf(.99f, -.99f), next)
    }

    @Test fun `software volume supplies headroom before the catcher sees peaks`() {
        val limiter = DesktopOutputLimiter().apply { configure(format) }
        val next = floatArrayOf(1.5f, -1.5f, .25f, -.25f)
        limiter.process(next, next.size, .5f)
        assertContentEquals(floatArrayOf(.75f, -.75f, .125f, -.125f), next)
    }
}
