package com.chamet.guesser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 / Session 2 — pre-race road banner reader (text only, never texture).
 * Simulates the 6 banner strings OCR is expected to see, plus common misreads.
 */
class Session2RoadTests {

    // ---- all 6 banners ----

    @Test fun allSixCanonicalNamesMatch() {
        for (road in SpeedDatabase.ROADS) {
            assertEquals(road, RoadMatcher.match(road))
            assertEquals(road, RoadMatcher.canonical(road))
            assertTrue(RoadMatcher.isKnown(road))
        }
        assertEquals(6, SpeedDatabase.ROADS.size)
    }

    @Test fun bannerAliases() {
        assertEquals("Highway", RoadMatcher.match("HIGHWAY"))
        assertEquals("Highway", RoadMatcher.match("Hwy"))
        assertEquals("Expressway", RoadMatcher.match("Express Way"))
        assertEquals("Expressway", RoadMatcher.match("express"))
        assertEquals("Dirt", RoadMatcher.match("DIRT ROAD"))
        assertEquals("Bumpy", RoadMatcher.match("Bumpy Road"))
        assertEquals("Potholes", RoadMatcher.match("Pot Holes"))
        assertEquals("Potholes", RoadMatcher.match("pothole"))
        assertEquals("Desert", RoadMatcher.match("DESERT"))
        assertEquals("Desert", RoadMatcher.match("sand"))
    }

    @Test fun ocrNoiseAndEditDistance() {
        // single-character OCR slips within edit threshold 2
        assertEquals("Highway", RoadMatcher.match("Hlghway"))
        assertEquals("Desert", RoadMatcher.match("Desrt"))
        assertEquals("Bumpy", RoadMatcher.match("Bumqy"))
        assertEquals("Potholes", RoadMatcher.match("Potholes!"))
        assertEquals("Expressway", RoadMatcher.match("Expressway."))
    }

    @Test fun unknownTextReturnsNull() {
        assertNull(RoadMatcher.match(""))
        assertNull(RoadMatcher.match("???"))
        assertNull(RoadMatcher.match("Finish Line"))
        assertNull(RoadMatcher.match("27s"))
        assertNull(RoadMatcher.canonical(null))
        assertNull(RoadMatcher.canonical("???") )
        assertFalse(RoadMatcher.isKnown("???"))
        assertFalse(RoadMatcher.isKnown(null))
    }

    @Test fun matchLinesPicksFirstRoad() {
        assertEquals(
            "Desert",
            RoadMatcher.matchLines(listOf("Round 4", "DESERT", "27s"))
        )
        assertNull(RoadMatcher.matchLines(listOf("27s", "BET", "???") ))
    }

    // ---- slot from banner x (fractions of screen width) ----

    @Test fun estimateSlotLeftMidRight() {
        val w = 1080
        assertEquals(1, OCRHelper.estimateSlot(w, 100f))
        assertEquals(1, OCRHelper.estimateSlot(w, 300f))
        assertEquals(2, OCRHelper.estimateSlot(w, 540f))
        assertEquals(3, OCRHelper.estimateSlot(w, 900f))
        assertEquals(null, OCRHelper.estimateSlot(w, null))   // unknown — do not invent mid
        assertEquals(null, OCRHelper.estimateSlot(0, 100f))
    }

    // ---- pool sum (post-close check only; pure helper) ----

    @Test fun poolSumAddsThreeSlots() {
        assertEquals(6L, OCRHelper.poolSum(listOf(1L, 2L, 3L)))
        assertEquals(0L, OCRHelper.poolSum(emptyList()))
    }

    // ---- Assets cover every known road ----

    @Test fun everyRoadHasAnImage() {
        for (road in SpeedDatabase.ROADS) {
            assertNotNull("$road missing from Assets.ROAD_RES", Assets.ROAD_RES[road])
        }
        assertEquals(SpeedDatabase.ROADS.toSet(), Assets.ROAD_RES.keys)
    }
}
