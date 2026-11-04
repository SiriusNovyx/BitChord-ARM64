package com.music.bitchord.desktop

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopRateConverterTailTest {
    @Test fun `finite streams keep their rational duration including sub-kernel inputs`() {
        for ((from, to) in listOf(44_100 to 48_000, 48_000 to 44_100, 96_000 to 8_000, 8_000 to 192_000)) {
            for (frames in listOf(0, 1, 2, 31, 127, 997, 2_048)) {
                val input = signal(frames, from)
                val output = convert(input, from, to, blockFrames = 37)
                val expected = ((frames.toLong() * to + from - 1) / from).toInt() * 2
                assertEquals(expected, output.size, "$frames frames at $from → $to")
                assertTrue(output.all { it.isFinite() })
            }
        }
    }

    @Test fun `the complete waveform including its endpoint is independent of decoder block size`() {
        for ((from, to) in listOf(44_100 to 48_000, 48_000 to 44_100, 96_000 to 44_100)) {
            val input = signal(2_048, from)
            val reference = convert(input, from, to, 2_048)
            for (block in listOf(1, 31, 127, 1_024)) {
                val actual = convert(input, from, to, block)
                assertEquals(reference.size, actual.size)
                for (index in reference.indices) assertEquals(reference[index], actual[index], .00001f,
                    "$from → $to, block $block, sample $index")
            }
        }
    }

    @Test fun `the tail retains channel independence`() {
        val output = convert(signal(997, 44_100), 44_100, 48_000, 31)
        for (frame in 0 until output.size / 2) assertTrue(abs(output[frame * 2] + output[frame * 2 + 1]) < .000001f,
            "Opposite-polarity channels must remain opposite through the filter tail")
    }

    private fun signal(frames: Int, rate: Int) = FloatArray(frames * 2) { index ->
        val value = (.15 + .1 * sin(2 * PI * 440 * (index / 2) / rate)).toFloat()
        if (index % 2 == 0) value else -value
    }

    private fun convert(input: FloatArray, from: Int, to: Int, blockFrames: Int): FloatArray {
        val converter = DesktopRateConverter(from, to, 2)
        val collected = ArrayList<Float>()
        var offset = 0
        while (offset < input.size) {
            val count = minOf(blockFrames * 2, input.size - offset)
            val output = converter.process(input.copyOfRange(offset, offset + count), count)
            repeat(converter.outputCount) { collected += output[it] }
            offset += count
        }
        val tail = converter.finish()
        repeat(converter.outputCount) { collected += tail[it] }
        repeat(3) { converter.finish(); assertEquals(0, converter.outputCount, "Repeated EOF must not replay audio") }
        return collected.toFloatArray()
    }
}
