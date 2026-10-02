package com.chamet.guesser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Session5Tests {

    private var clock = 0L

    private fun round(
        won: Boolean?,
        winner: String? = null,
        winnerPosition: Int? = null,
        v: List<String> = listOf("ATV", "Car", "SUV"),
        pos: List<String> = listOf("Car", "ATV", "SUV"),
        r1: String = "Desert", r2: String? = null, r3: String? = null
    ): RoundEntity {
        clock += 1000
        return RoundEntity(
            timestamp = clock, year = 2026, month = 10, day = 1, hour = 12, minute = 0, second = 0,
            dayOfWeek = 4, timeSinceLastRound = 0, sessionId = 1,
            r1 = r1, r2 = r2, r3 = r3,
            carPosition1 = pos[0], carPosition2 = pos[1], carPosition3 = pos[2],
            v1 = v[0], v2 = v[1], v3 = v[2],
            winner = winner, winnerPosition = winnerPosition, won = won
        )
    }

    // ---- overview ----

    @Test fun noBetRoundsAreNotCountedInWinRate() {
        val rows = listOf(round(true), round(null), round(false), round(null))
        val o = AnalyticsStats.overview(rows)
        assertEquals(4, o.total)
        assertEquals(2, o.bet)
        assertEquals(2, o.noBet)
        assertEquals(50.0, o.winPct, 0.001)
    }

    @Test fun noBetRoundDoesNotBreakAStreak() {
        // oldest -> newest: win, no bet, win
        val rows = listOf(round(true), round(null), round(true)).reversed()   // DAO order is newest first
        val o = AnalyticsStats.overview(rows)
        assertEquals(2, o.currentStreak)
        assertEquals(2, o.longestStreak)
    }

    @Test fun lossResetsStreak() {
        val rows = listOf(round(true), round(true), round(false), round(true))
        val o = AnalyticsStats.overview(rows)
        assertEquals(1, o.currentStreak)
        assertEquals(2, o.longestStreak)
    }

    // ---- road ----

    @Test fun roadIsTheOneThatWasPicked() {
        assertEquals("Desert", AnalyticsStats.roadOf(round(true, r1 = "Desert")))
        assertEquals("Snow", AnalyticsStats.roadOf(round(true, r1 = "???", r2 = "Snow")))
        assertEquals("Mud", AnalyticsStats.roadOf(round(true, r1 = "???", r2 = null, r3 = "Mud")))
        assertEquals("???", AnalyticsStats.roadOf(round(true, r1 = "???")))
    }

    // ---- winner rank / position ----

    @Test fun winnerRankIsPlaceInOurRanking() {
        assertEquals(1, AnalyticsStats.winnerRank(round(true, winner = "ATV")))
        assertEquals(3, AnalyticsStats.winnerRank(round(false, winner = "SUV")))
        assertNull(AnalyticsStats.winnerRank(round(false)))
        assertNull(AnalyticsStats.winnerRank(round(false, winner = "Motorcycle")))
    }

    @Test fun winnerPositionIsScreenColumn() {
        assertEquals(2, AnalyticsStats.winnerPosition(round(true, winner = "ATV", winnerPosition = 2)))
        // column missing in the row: fall back to the car's place in carPosition1..3
        assertEquals(3, AnalyticsStats.winnerPosition(round(true, winner = "SUV")))
        assertNull(AnalyticsStats.winnerPosition(round(false)))
    }

    @Test fun rankAndPositionAreIndependent() {
        // ATV is our 1st pick but sits in the middle of the screen
        val r = round(true, winner = "ATV", winnerPosition = 2)
        assertEquals(1, AnalyticsStats.winnerRank(r))
        assertEquals(2, AnalyticsStats.winnerPosition(r))
    }

    @Test fun distCountsOnlyRoundsWithKnownWinner() {
        val rows = listOf(
            round(true, winner = "ATV"), round(true, winner = "ATV"), round(false, winner = "SUV"),
            round(false), round(null)
        )
        val d = AnalyticsStats.rankDist(rows)
        assertEquals(3, d.known)
        assertEquals(listOf(2, 0, 1), d.counts)
        assertEquals(66.666, d.pct(0), 0.01)
    }

    // ---- bias ----

    private fun rowsWithWinnerPositions(p1: Int, p2: Int, p3: Int): List<RoundEntity> =
        List(p1) { round(true, winner = "Car", winnerPosition = 1) } +
        List(p2) { round(true, winner = "ATV", winnerPosition = 2) } +
        List(p3) { round(false, winner = "SUV", winnerPosition = 3) }

    @Test fun biasWaitsForThirtyRounds() =
        assertNull(AnalyticsStats.biasMultipliers(rowsWithWinnerPositions(10, 10, 9)))

    @Test fun rightColumnBiasBoostsP3() {
        val m = AnalyticsStats.biasMultipliers(rowsWithWinnerPositions(8, 8, 14))   // P3 = 46.7%
        assertEquals(Triple(0.85, 1.0, 1.25), m)
    }

    @Test fun leftColumnBiasBoostsP1() {
        val m = AnalyticsStats.biasMultipliers(rowsWithWinnerPositions(14, 8, 8))
        assertEquals(Triple(1.25, 1.0, 0.85), m)
    }

    @Test fun evenSpreadIsNeutral() {
        val m = AnalyticsStats.biasMultipliers(rowsWithWinnerPositions(10, 10, 10))
        assertEquals(Triple(1.0, 1.0, 1.0), m)
    }

    @Test fun lossRowsWithKnownWinnerCountTowardBias() {
        // all 32 rounds are losses where the real winner was tapped: still enough data
        val rows = List(32) { round(false, winner = "SUV", winnerPosition = 3) }
        assertNotNull(AnalyticsStats.biasMultipliers(rows))
    }

    // ---- report ----

    @Test fun emptyReport() =
        assertEquals("No rounds logged yet.", AnalyticsStats.report(emptyList(), 100_000L, ""))

    @Test fun reportHasTheNewSections() {
        val rows = listOf(round(true, winner = "ATV", winnerPosition = 2), round(null))
        val text = AnalyticsStats.report(rows, 100_000L, "")
        assertTrue(text.contains("No-bet rounds:      1"))
        assertTrue(text.contains("=== Winner rank (our ranking) ==="))
        assertTrue(text.contains("=== Winner screen position ==="))
        assertTrue(text.contains("1st pick won: 1 (100.0%)"))
        assertTrue(text.contains("Bias needs 30 rounds"))
    }
}
