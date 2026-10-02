package com.chamet.guesser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 / Session 10 — acceptance gates and freeze checks.
 */
class Session10Tests {

    private fun row(
        i: Int,
        roadSource: String? = "screen",
        winner: String? = "ATV",
        winnerSource: String? = "manual",
        v1: String = "ATV",
        modelWinner: String? = "ATV",
        paramsVersion: String? = "8.1.0"
    ) = RoundEntity(
        timestamp = 1_000L + i,
        year = 2026, month = 10, day = 2,
        hour = 12, minute = 0, second = 0, dayOfWeek = 5,
        timeSinceLastRound = 0, sessionId = 1,
        r1 = "Desert", v1 = v1, v2 = "Car", v3 = "SUV",
        winner = winner, winnerSource = winnerSource, won = true,
        roadSource = roadSource, modelWinner = modelWinner,
        paramsVersion = paramsVersion
    )

    @Test fun emptyFailsMinRounds() {
        val r = Phase1Gates.evaluate(emptyList())
        assertFalse(r.allPassed)
        assertTrue(r.gates.any { it.id == "min_rounds" && !it.passed })
    }

    @Test fun healthyBatchPasses() {
        val rows = List(25) { row(it) }
        val r = Phase1Gates.evaluate(rows)
        assertTrue(r.text().contains("Phase 1 acceptance"))
        val min = r.gates.first { it.id == "min_rounds" }
        assertTrue(min.passed)
        val lost = r.gates.first { it.id == "no_lost_rounds" }
        assertTrue(lost.passed)
        val manual = r.gates.first { it.id == "manual_road_rate" }
        assertTrue(manual.passed) // 0% manual
    }

    @Test fun highManualRoadRateFails() {
        val rows = List(25) { row(it, roadSource = "manual") }
        val r = Phase1Gates.evaluate(rows)
        val g = r.gates.first { it.id == "manual_road_rate" }
        assertFalse(g.passed)
        assertFalse(r.allPassed)
    }

    @Test fun brokenRowFailsNoLost() {
        val rows = List(25) { row(it) }.toMutableList()
        rows[0] = rows[0].copy(v1 = "", timestamp = 0L)
        val r = Phase1Gates.evaluate(rows)
        assertFalse(r.gates.first { it.id == "no_lost_rounds" }.passed)
    }

    @Test fun modelAccuracyGateAlwaysReports() {
        val rows = List(25) { row(it, modelWinner = "ATV", winner = "ATV") }
        val r = Phase1Gates.evaluate(rows)
        val g = r.gates.first { it.id == "model_accuracy_reported" }
        assertTrue(g.passed)
        assertTrue(g.detail.contains("hits="))
    }

    @Test fun analyticsIncludesAcceptanceBlock() {
        val rows = List(25) { row(it) }
        val text = AnalyticsStats.report(rows, 100_000L, "")
        assertTrue(text.contains("Phase 1 acceptance"))
        assertTrue(text.contains("min_rounds"))
    }

    @Test fun versionIs810() {
        // Documented freeze target
        assertEquals("8.1.0", Phase1Gates.Report(emptyList(), false).version)
    }
}
