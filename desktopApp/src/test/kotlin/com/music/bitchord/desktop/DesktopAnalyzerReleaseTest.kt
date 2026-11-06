package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.BeatTracker
import com.music.bitchord.playback.smart.MelSpectrogram
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAnalyzerReleaseTest {
    @Test fun `release retires the created model after existing worker work returns`() {
        DesktopAnalysisRuntime.ensureStarted()
        assertTrue(DesktopAnalysisRuntime.available, "This regression requires the packaged native analyser")
        val worker = Executors.newSingleThreadExecutor()
        val analyzer = DesktopTrackAnalyzer(worker = worker)
        val getter = DesktopTrackAnalyzer::class.java.getDeclaredMethod("getBeats").apply { isAccessible = true }
        val tracker = getter.invoke(analyzer) as BeatTracker
        val session = BeatTracker::class.java.getDeclaredField("session").apply { isAccessible = true }
        val entered = CountDownLatch(1)
        val leave = CountDownLatch(1)
        try {
            tracker.track(FloatArray(MelSpectrogram.sampleRate.toInt()))
            assertNotNull(session.get(tracker), "The real model must be open before testing its retirement")
            worker.execute {
                entered.countDown()
                // A native call may ignore interruption until it returns.
                while (leave.count != 0L) try { leave.await() } catch (_: InterruptedException) { }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            analyzer.release()
            assertNotNull(session.get(tracker), "Closing a model must not race work already using the worker")
            leave.countDown()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            assertNull(session.get(tracker), "Analyzer release must also retire its native model session")
        } finally {
            leave.countDown()
            worker.shutdownNow()
            worker.awaitTermination(5, TimeUnit.SECONDS)
            tracker.release()
        }
    }
}
