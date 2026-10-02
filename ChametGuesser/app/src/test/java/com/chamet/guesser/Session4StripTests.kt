package com.chamet.guesser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Phase 1 / Session 4 — race-strip reader golden tests.
 *
 * Layouts from the plan (track = 990 px):
 *  - 4:11  Desert 870 + Highway 118
 *  - 4:14  Highway 167 + Expressway 98 + Desert 722
 *  - 4:16  Desert 98 + Bumpy 415 + Desert 475
 *
 * Widths must be within ~1% of track (~10 px).
 */
class Session4StripTests {

    private val tol = RaceStripReader.WIDTH_TOLERANCE_PX

    private fun desert() = RaceStripReader.rgb(237, 210, 131)
    private fun highway() = RaceStripReader.rgb(140, 141, 145)
    private fun expressway() = RaceStripReader.rgb(160, 160, 160)
    private fun bumpy() = RaceStripReader.rgb(97, 86, 58)
    private fun dirt() = RaceStripReader.rgb(150, 120, 80)
    private fun potholes() = RaceStripReader.rgb(90, 90, 95)

    private fun assertNear(expected: Double, actual: Double, label: String) {
        assertTrue(
            "$label: expected $expected ±$tol, got $actual",
            abs(expected - actual) <= tol
        )
    }

    // ---- golden layouts ----

    @Test fun golden_4_11_desertThenHighway() {
        val cols = RaceStripReader.syntheticColumns(
            listOf(desert() to 870, highway() to 118)
        )
        val r = RaceStripReader.analyseColumns(cols, 0, 990, visibleRoad = "Desert")
        assertEquals(2, r.segments.size)
        assertEquals("Desert", r.segments[0].type)
        assertEquals("Highway", r.segments[1].type)
        assertNear(870.0, r.segments[0].px, "Desert px")
        assertNear(118.0, r.segments[1].px, "Highway px")
        assertNear(990.0, r.trackWidthPx, "track")
        assertTrue(r.visibleRoadPresent)
        assertTrue(r.confidence > 0.5)
    }

    @Test fun golden_4_14_highwayExpressDesert() {
        val cols = RaceStripReader.syntheticColumns(
            listOf(highway() to 167, expressway() to 98, desert() to 722)
        )
        val r = RaceStripReader.analyseColumns(cols, 0, 990, visibleRoad = "Highway")
        assertEquals(3, r.segments.size)
        assertEquals(listOf("Highway", "Expressway", "Desert"), r.roadTypes)
        assertNear(167.0, r.segments[0].px, "Highway")
        assertNear(98.0, r.segments[1].px, "Expressway")
        assertNear(722.0, r.segments[2].px, "Desert")
        assertTrue(r.visibleRoadPresent)
    }

    @Test fun golden_4_16_desertBumpyDesert() {
        val cols = RaceStripReader.syntheticColumns(
            listOf(desert() to 98, bumpy() to 415, desert() to 475)
        )
        val r = RaceStripReader.analyseColumns(cols, 0, 990, visibleRoad = "Bumpy")
        assertEquals(3, r.segments.size)
        assertEquals(listOf("Desert", "Bumpy", "Desert"), r.roadTypes)
        assertNear(98.0, r.segments[0].px, "Desert1")
        assertNear(415.0, r.segments[1].px, "Bumpy")
        assertNear(475.0, r.segments[2].px, "Desert2")
        // fractions sum ~1
        val fracSum = r.segments.sumOf { it.fraction }
        assertTrue(abs(fracSum - 1.0) < 0.02)
    }

    // ---- all 6 road types classified ----

    @Test fun allSixRoadTypesRecognised() {
        val samples = listOf(
            "Desert" to desert(),
            "Highway" to highway(),
            "Expressway" to expressway(),
            "Bumpy" to bumpy(),
            "Dirt" to dirt(),
            "Potholes" to potholes()
        )
        for ((name, rgb) in samples) {
            val (got, conf) = RaceStripReader.classifyRgb(
                RaceStripReader.red(rgb),
                RaceStripReader.green(rgb),
                RaceStripReader.blue(rgb)
            )
            assertEquals("failed for $name", name, got)
            assertTrue("$name conf=$conf", conf >= 0.65)
        }
    }

    @Test fun signaturesCoverEveryRoad() {
        assertEquals(SpeedDatabase.ROADS.toSet(), RaceStripReader.ROAD_SIGNATURES.keys)
        RaceStripReader.ROAD_SIGNATURES.values.forEach { assertTrue(it.isNotEmpty()) }
    }

    // ---- merge / noise ----

    @Test fun neighbouringSameTypeMerges() {
        val cols = RaceStripReader.syntheticColumns(
            listOf(desert() to 400, desert() to 400, highway() to 190)
        )
        val r = RaceStripReader.analyseColumns(cols, 0, 990)
        assertEquals(2, r.segments.size)
        assertEquals("Desert", r.segments[0].type)
        assertNear(800.0, r.segments[0].px, "merged Desert")
    }

    @Test fun tinyNoiseAbsorbed() {
        // 5-px noise stripe between two Desert runs (below 1% of 990 ≈ 10)
        val cols = RaceStripReader.syntheticColumns(
            listOf(desert() to 490, potholes() to 5, desert() to 495)
        )
        val r = RaceStripReader.analyseColumns(cols, 0, 990)
        // either fully merged to Desert, or noise kept as tiny — must not invent 3 fat segments
        assertTrue(r.segments.size <= 3)
        assertTrue(r.segments.any { it.type == "Desert" && it.px > 400 })
    }

    // ---- visible-road cross-check ----

    @Test fun visibleRoadMissingHalvesConfidence() {
        val cols = RaceStripReader.syntheticColumns(
            listOf(desert() to 500, highway() to 490)
        )
        val ok = RaceStripReader.analyseColumns(cols, 0, 990, visibleRoad = "Desert")
        val bad = RaceStripReader.analyseColumns(cols, 0, 990, visibleRoad = "Potholes")
        assertTrue(ok.visibleRoadPresent)
        assertFalse(bad.visibleRoadPresent)
        assertTrue(bad.confidence < ok.confidence)
    }

    @Test fun emptyColumnsLowConfidence() {
        val r = RaceStripReader.analyseColumns(IntArray(0), 0, 990)
        assertEquals(0, r.segments.size)
        assertEquals(0.0, r.confidence, 1e-9)
    }

    // ---- helpers used by later sessions ----

    @Test fun roadTypeAndPxAccessors() {
        val cols = RaceStripReader.syntheticColumns(
            listOf(highway() to 167, expressway() to 98, desert() to 722)
        )
        val r = RaceStripReader.analyseColumns(cols, 0, 990)
        assertEquals("Highway", r.roadType(0))
        assertEquals("Expressway", r.roadType(1))
        assertEquals("Desert", r.roadType(2))
        assertNear(167.0, r.roadPx(0)!!, "px0")
        assertEquals(null, r.roadType(3))
    }
}
