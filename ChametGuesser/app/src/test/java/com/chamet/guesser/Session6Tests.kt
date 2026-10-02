package com.chamet.guesser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Phase 1 / Session 6 — auto result, finish-time model, model accuracy.
 * Golden finish times from the plan (track px ÷ table speed).
 */
class Session6Tests {

    // ---- FinishTimeModel golden layouts ----

    @Test fun golden_4_11_times() {
        // Desert 870 + Highway 118
        val segs = listOf("Desert" to 870.0, "Highway" to 118.0)
        val r = FinishTimeModel.compute(listOf("SUV", "Monster Truck", "Sports Car"), segs)!!
        assertEquals("SUV", r.modelWinner)
        // Plan approx: SUV 16.1, Monster Truck 17.0, Sports Car 19.8
        assertNear(16.1, r.times.first { it.car == "SUV" }.time, 0.3)
        assertNear(17.0, r.times.first { it.car == "Monster Truck" }.time, 0.3)
        assertNear(19.8, r.times.first { it.car == "Sports Car" }.time, 0.3)
        assertEquals(listOf("SUV", "Monster Truck", "Sports Car"), r.times.map { it.car })
    }

    @Test fun golden_4_14_times() {
        val segs = listOf(
            "Highway" to 167.0, "Expressway" to 98.0, "Desert" to 722.0
        )
        val r = FinishTimeModel.compute(
            listOf("Motorcycle", "Monster Truck", "Supercar"), segs
        )!!
        assertEquals("Motorcycle", r.modelWinner)
        assertNear(14.2, r.times.first { it.car == "Motorcycle" }.time, 0.3)
        assertNear(15.6, r.times.first { it.car == "Monster Truck" }.time, 0.3)
        assertNear(18.9, r.times.first { it.car == "Supercar" }.time, 0.3)
    }

    @Test fun golden_4_16_times() {
        val segs = listOf(
            "Desert" to 98.0, "Bumpy" to 415.0, "Desert" to 475.0
        )
        val r = FinishTimeModel.compute(
            listOf("Motorcycle", "Car", "Supercar"), segs
        )!!
        assertEquals("Motorcycle", r.modelWinner)
        assertNear(14.2, r.times.first { it.car == "Motorcycle" }.time, 0.3)
        assertNear(17.0, r.times.first { it.car == "Car" }.time, 0.3)
        assertNear(21.2, r.times.first { it.car == "Supercar" }.time, 0.3)
    }

    @Test fun encodeDecodeModelTimes() {
        val r = FinishTimeModel.compute(
            listOf("ATV", "Car"),
            listOf("Desert" to 500.0)
        )!!
        val map = FinishTimeModel.decodeTimes(r.modelTimesEncoded)
        assertTrue(map.containsKey("ATV"))
        assertTrue(map.containsKey("Car"))
        assertTrue(FinishTimeModel.modelHit(r.modelWinner, r.modelWinner))
        assertFalse(FinishTimeModel.modelHit(r.modelWinner, "NoSuchCar"))
    }

    // ---- AutoResult resolution ----

    @Test fun manualOverrideWins() {
        val r = AutoResult.resolve(
            manualWinner = "Car",
            manualWon = true,
            trackWinner = "ATV",
            trackOrder = listOf("ATV", "Car", "SUV"),
            positionCars = listOf("ATV", "Car", "SUV"),
            backedCars = listOf("Car")
        )
        assertEquals("Car", r.winner)
        assertEquals(true, r.won)
        assertEquals(AutoResult.SOURCE_MANUAL, r.winnerSource)
        assertEquals(2, r.winnerPosition)
    }

    @Test fun trackWinnerWhenNoManual() {
        val r = AutoResult.resolve(
            trackOrder = listOf("Motorcycle", "Car", "Supercar"),
            positionCars = listOf("Car", "Motorcycle", "Supercar"),
            backedCars = listOf("Motorcycle")
        )
        assertEquals("Motorcycle", r.winner)
        assertEquals(true, r.won)
        assertEquals(AutoResult.SOURCE_TRACK, r.winnerSource)
        assertEquals(2, r.winnerPosition) // mid card
    }

    @Test fun trackWinnerNotBackedIsLoss() {
        val r = AutoResult.resolve(
            trackWinner = "SUV",
            trackOrder = listOf("SUV", "Car"),
            backedCars = listOf("Car")
        )
        assertEquals("SUV", r.winner)
        assertEquals(false, r.won)
        assertEquals(AutoResult.SOURCE_TRACK, r.winnerSource)
    }

    @Test fun balanceBackupWin() {
        val r = AutoResult.resolve(
            balanceBefore = 100_000,
            balanceAfter = 105_000,
            backedCars = listOf("ATV")
        )
        assertEquals(true, r.won)
        assertEquals(AutoResult.SOURCE_BALANCE, r.winnerSource)
        assertNull(r.winner)
    }

    @Test fun unknownDoesNotBlock() {
        val r = AutoResult.resolve()
        assertEquals(AutoResult.SOURCE_UNKNOWN, r.winnerSource)
        assertNull(r.winner)
        assertNull(r.won)
    }

    @Test fun noBetKeepsWonNull() {
        val r = AutoResult.resolve(noBet = true, trackWinner = "Car")
        assertNull(r.won)
        assertEquals("Car", r.winner)
        assertEquals(AutoResult.SOURCE_TRACK, r.winnerSource)
    }

    // ---- model accuracy over rows ----

    private fun row(
        winner: String?,
        model: String?,
        source: String? = "track",
        finish: String? = null
    ) = RoundEntity(
        timestamp = 1L, year = 2026, month = 10, day = 2,
        hour = 12, minute = 0, second = 0, dayOfWeek = 5,
        timeSinceLastRound = 0, sessionId = 1,
        r1 = "Desert", v1 = "ATV",
        winner = winner, modelWinner = model,
        winnerSource = source, finishOrder = finish,
        won = if (winner != null) true else null
    )

    @Test fun modelAccuracyCountsHits() {
        val rows = listOf(
            row("ATV", "ATV"),
            row("Car", "ATV"),
            row("SUV", "SUV"),
            row(null, "ATV"),       // no real winner — skipped
            row("ATV", null)        // no model — skipped
        )
        val (hits, known, pct) = AutoResult.modelAccuracy(rows)
        assertEquals(2, hits)
        assertEquals(3, known)
        assertEquals(66.666, pct, 0.1)
    }

    @Test fun trackAgreementVsManual() {
        val rows = listOf(
            row("ATV", "ATV", "manual", "ATV|Car|SUV"),
            row("Car", "ATV", "manual", "ATV|Car|SUV"), // track said ATV, manual Car
            row("ATV", "ATV", "track", "ATV|Car")       // not manual — skipped
        )
        val (hits, known, pct) = AutoResult.trackAgreement(rows)
        assertEquals(1, hits)
        assertEquals(2, known)
        assertEquals(50.0, pct, 0.01)
    }

    private fun assertNear(expected: Double, actual: Double, tol: Double) {
        assertTrue(
            "expected $expected ±$tol, got $actual",
            abs(expected - actual) <= tol
        )
    }
}
