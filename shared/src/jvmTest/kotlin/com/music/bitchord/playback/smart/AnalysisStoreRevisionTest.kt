package com.music.bitchord.playback.smart

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.*

class AnalysisStoreRevisionTest {
    private val analysis = TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = "revision-test",
        duration = 60.0, bpm = 120.0, beatInterval = .5, beatConfidence = .8)

    @Test fun anUnversionedGridCannotInheritTheCurrentAlgorithmRevision() {
        val home = Files.createTempDirectory("bitchord-analysis-revision-").toFile()
        try {
            AnalysisStore { home }.save(analysis.trackId, analysis)
            val stored = home.walkTopDown().single { it.isFile && it.extension == "json" }
            stored.writeText(JsonObject(Json.parseToJsonElement(stored.readText()).jsonObject - "version").toString())
            assertNull(AnalysisStore { home }.load(analysis.trackId), "Missing provenance cannot mean the current algorithm")
        } finally { home.deleteRecursively() }
    }

    @Test fun freshAnalysisRecordsTheSideGridAndWindowRevision() {
        val home = Files.createTempDirectory("bitchord-analysis-revision-").toFile()
        try {
            AnalysisStore { home }.save(analysis.trackId, analysis)
            val stored = home.walkTopDown().single { it.isFile && it.extension == "json" }
            assertEquals(3, Json.parseToJsonElement(stored.readText()).jsonObject.getValue("version").jsonPrimitive.int)
            assertEquals(120.0, assertNotNull(AnalysisStore { home }.load(analysis.trackId)).bpm)
        } finally { home.deleteRecursively() }
    }

    @Test fun sideGridsAndMeasuredWindowsSurviveAStoreRoundTrip() {
        val home = Files.createTempDirectory("bitchord-analysis-windows-").toFile()
        try {
            val measured = analysis.copy(
                headBeats = BeatSide(listOf(.5, 1.0), listOf(.5), 120.0, .8),
                tailBeats = BeatSide(listOf(58.0, 58.5), listOf(58.0), 120.0, .9),
                vocalWindows = listOf(CurveWindow(1.25, 50.0, floatArrayOf(.12f, .98f))),
                levelWindows = listOf(CurveWindow(58.0, LEVEL_FRAME_RATE, floatArrayOf(.25f, .75f))),
            )
            AnalysisStore { home }.save(measured.trackId, measured)
            val restored = assertNotNull(AnalysisStore { home }.load(measured.trackId))
            assertEquals(measured.headBeats, restored.headBeats)
            assertEquals(measured.tailBeats, restored.tailBeats)
            val vocal = restored.vocalWindows.single()
            assertEquals(1.25, vocal.startTime)
            assertEquals(50.0, vocal.frameRate)
            kotlin.test.assertContentEquals(floatArrayOf(.12f, .98f), vocal.values)
            val level = restored.levelWindows.single()
            assertEquals(58.0, level.startTime)
            assertEquals(LEVEL_FRAME_RATE, level.frameRate)
            kotlin.test.assertContentEquals(floatArrayOf(.25f, .75f), level.values)
        } finally { home.deleteRecursively() }
    }

    @Test fun revisionTwoCannotSupplyMissingV19LoopMeasurements() {
        val home = Files.createTempDirectory("bitchord-analysis-old-windows-").toFile()
        try {
            val preferences = home.resolve("preferences.keep").apply { writeText("existing-user-settings") }
            AnalysisStore { home }.save(analysis.trackId, analysis)
            val stored = home.walkTopDown().single { it.isFile && it.extension == "json" }
            val json = Json.parseToJsonElement(stored.readText()).jsonObject
            stored.writeText(JsonObject(json + ("version" to JsonPrimitive(2))).toString())
            assertNull(AnalysisStore { home }.load(analysis.trackId))
            assertEquals("existing-user-settings", preferences.readText())
        } finally { home.deleteRecursively() }
    }

    @Test fun anOldPeakGridIsReanalysedWithoutChangingOtherSavedState() {
        val home = Files.createTempDirectory("bitchord-analysis-revision-").toFile()
        try {
            val otherState = home.resolve("preferences.keep").apply { writeText("existing-user-settings") }
            AnalysisStore { home }.save(analysis.trackId, analysis)
            val stored = home.walkTopDown().single { it.isFile && it.extension == "json" }
            val json = Json.parseToJsonElement(stored.readText()).jsonObject
            stored.writeText(JsonObject(json + ("version" to JsonPrimitive(1))).toString())
            val updated = AnalysisStore { home }
            assertNull(updated.load(analysis.trackId), "A grid computed by the old peak picker is not current evidence")
            updated.save(analysis.trackId, analysis.copy(bpm = 125.0))
            assertEquals(125.0, assertNotNull(updated.load(analysis.trackId)).bpm)
            assertEquals("existing-user-settings", otherState.readText())
        } finally { home.deleteRecursively() }
    }
}
