package com.chamet.guesser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * New plan Sessions 3–5 unit coverage (no device data).
 */
class Session345Tests {

    @Test fun raceLoopStripThenTrackThenRecheck() {
        var t = 1_000L // non-zero: 0 is the "unset" sentinel in the controller
        val c = RaceLoopController(trackIntervalMs = 250L, stripRecheckAfterMs = 1_000L, nowMs = { t })
        c.onRaceStarted()
        assertEquals(RaceLoopController.Work.STRIP, c.nextWork(true))
        assertEquals(RaceLoopController.Work.TRACK, c.nextWork(true)) // first track sample
        assertEquals(RaceLoopController.Work.NONE, c.nextWork(true)) // same instant
        t = 1_250
        assertEquals(RaceLoopController.Work.TRACK, c.nextWork(true))
        t = 2_000
        // after track interval also recheck once
        val w = c.nextWork(true)
        assertTrue(w == RaceLoopController.Work.STRIP_RECHECK || w == RaceLoopController.Work.TRACK)
        assertTrue(c.expectedSamples(15_000) >= 50) // ~4 fps * 15s
    }

    @Test fun finishMatcherFindsWinnerNearWin() {
        val cars = listOf("ATV", "Car", "SUV")
        assertEquals(
            "ATV",
            FinishScreenMatcher.matchFromText("Winner ATV  1st place", cars)
        )
        assertNull(FinishScreenMatcher.matchFromText("Highway Desert", cars))
        assertNull(FinishScreenMatcher.matchFromPixels(IntArray(0), 0, 0, cars))
    }

    @Test fun dataIntegrityBackfillAndMigrationFlags() {
        val rows = listOf(
            RoundEntity(
                timestamp = 1, year = 2026, month = 1, day = 1,
                hour = 0, minute = 0, second = 0, dayOfWeek = 1,
                timeSinceLastRound = 0, sessionId = 1,
                r1 = "Desert", v1 = "ATV", roundUuid = ""
            ),
            RoundEntity(
                timestamp = 2, year = 2026, month = 1, day = 1,
                hour = 0, minute = 0, second = 0, dayOfWeek = 1,
                timeSinceLastRound = 0, sessionId = 1,
                r1 = "Desert", v1 = "Car", roundUuid = "already"
            )
        )
        val filled = DataIntegrity.backfillRoundUuids(rows)
        assertTrue(filled[0].roundUuid.isNotBlank())
        assertEquals("already", filled[1].roundUuid)
        assertTrue(DataIntegrity.migrationCoversRoundUuid())
        assertTrue(DataIntegrity.migrationIsNonDestructive())
    }

    @Test fun measuredLengthPriorNeedsSamples() {
        assertNull(DataIntegrity.measuredLengthPrior(emptyList()))
        val rows = List(5) {
            RoundEntity(
                timestamp = it.toLong(), year = 2026, month = 1, day = 1,
                hour = 0, minute = 0, second = 0, dayOfWeek = 1,
                timeSinceLastRound = 0, sessionId = 1,
                r1 = "Desert", v1 = "ATV",
                roadType1 = "Desert", roadPx1 = 500.0,
                roadType2 = "Highway", roadPx2 = 490.0
            )
        }
        val prior = DataIntegrity.measuredLengthPrior(rows, minSegments = 8)
        assertNotNull(prior)
        assertTrue(prior!!.size >= 8)
    }

    @Test fun emptyGatesStillFail() {
        val r = Phase1Gates.evaluate(emptyList())
        assertFalse(r.allPassed)
    }

    @Test fun goldenFinishTimesStillHold() {
        val segs = listOf("Desert" to 870.0, "Highway" to 118.0)
        val r = FinishTimeModel.compute(listOf("SUV", "Monster Truck", "Sports Car"), segs)!!
        assertEquals("SUV", r.modelWinner)
    }
}
