package com.music.bitchord.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class MixTransitionViewTest {
    private fun deck() = MixDeck("Track", "Artist", null, 120.0, "C major", 180_000, 0.0, 10.0)
    private fun source() = MixTransitionView(deck(), deck(), 1.0, 9.0, 2.0, 10.0, 16,
        floatArrayOf(0f, .5f, 1f), floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))

    @Test fun sourceTimelineIsTheBackwardCompatibleDefault() {
        val view = source()
        val getter = assertNotNull(view.javaClass.methods.find { it.name == "getTimeline" },
            "A waveform must declare whether its axis is source time or the actual mix clock")
        assertEquals("SOURCE", (getter.invoke(view) as Enum<*>).name)
    }

    @Test fun replacingMeasuredDeckWavesPreservesTheMixClockAxis() {
        val constructor = assertNotNull(MixTransitionView::class.java.constructors.find {
            it.parameterTypes.size == 12 && it.parameterTypes.last().isEnum
        }, "The mix-clock waveform axis must be an optional final model field")
        val timeline = constructor.parameterTypes.last().enumConstants.first { (it as Enum<*>).name == "MIX" }
        val view = constructor.newInstance(deck(), deck(), 0.0, 12.0, 0.0, 12.0, 16,
            floatArrayOf(0f, .5f, 1f), floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), .5f, timeline) as MixTransitionView
        val out = deck()
        val into = deck()
        val replaced = view.withDecks(out, into)
        assertSame(out, replaced.outgoing)
        assertSame(into, replaced.incoming)
        assertSame(timeline, view.javaClass.getMethod("getTimeline").invoke(replaced))
    }
}
