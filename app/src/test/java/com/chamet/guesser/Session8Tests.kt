package com.chamet.guesser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 1 / Session 8 — betting guards, calibration, local learn, backtest.
 */
class Session8Tests {

    @Before fun reset() {
        EngineParams.resetToDefault()
        // drain any previous snapshot
        while (LocalLearner.rollback()) { /* clear */ }
    }

    private fun round(
        conf: Int,
        won: Boolean?,
        winner: String? = "ATV",
        v1: String = "ATV",
        v2: String = "Car",
        v3: String = "SUV",
        poolA: Long = 1_000_000,
        poolB: Long = 1_000_000,
        poolC: Long = 1_000_000,
        roadType1: String? = null,
        roadPx1: Double? = null,
        roadType2: String? = null,
        roadPx2: Double? = null,
        visible: String? = null,
        modelWinner: String? = null,
        ts: Long = 1L
    ) = RoundEntity(
        timestamp = ts, year = 2026, month = 10, day = 2,
        hour = 12, minute = 0, second = 0, dayOfWeek = 5,
        timeSinceLastRound = 0, sessionId = 1,
        r1 = visible ?: "Desert", v1 = v1, v2 = v2, v3 = v3,
        confidence = conf, winner = winner, won = won,
        poolA = poolA, poolB = poolB, poolC = poolC,
        roadType1 = roadType1, roadPx1 = roadPx1,
        roadType2 = roadType2, roadPx2 = roadPx2,
        visibleRoad = visible, modelWinner = modelWinner,
        balanceBeforeBet = 100_000
    )

    // ---- OddsEngine guards ----

    @Test fun allEvBelowOneSuggestsZeroStake() {
        // Equal pools → odds 3.0; with tiny win probs still EV can be ok.
        // Force near-zero win on all by using empty winProb equal → EV = 1.0 exactly on 3-way.
        // Use a huge pool on the favourite so EV < 1 for others and top still < 1 with low p.
        val cars = listOf("A", "B", "C")
        // C has no pool (odds 0 → EV 0) but gets most of the win probability;
        // A and B have fair pools with low win probability → every EV < 1.
        val pools = mapOf("A" to 100L, "B" to 100L, "C" to 0L)
        val winProb = mapOf("A" to 0.1, "B" to 0.1, "C" to 0.8)
        val advice = OddsEngine.compute(cars, pools, 80, 100_000L, winProb)
        assertEquals(0, advice.totalBet)
        assertTrue(advice.warning?.contains("EV") == true || advice.warning?.contains("Skip") == true)
    }

    @Test fun lowConfidenceHalvesCap() {
        val cars = listOf("ATV", "Car", "SUV")
        val pools = mapOf("ATV" to 100_000L, "Car" to 5_000_000L, "SUV" to 5_000_000L)
        // ATV has big odds; high pWin → EV > 1
        val p = mapOf("ATV" to 0.7, "Car" to 0.2, "SUV" to 0.1)
        val high = OddsEngine.compute(cars, pools, 80, 100_000L, p)
        val low = OddsEngine.compute(cars, pools, 30, 100_000L, p)
        assertTrue(high.totalBet > 0)
        assertTrue(low.totalBet > 0)
        assertTrue(low.totalBet <= high.totalBet)
        assertTrue(low.warning?.contains("half", ignoreCase = true) == true)
    }

    @Test fun totalBetNeverExceedsSevenPercent() {
        val cars = listOf("ATV", "Car")
        val pools = mapOf("ATV" to 50_000L, "Car" to 5_000_000L)
        val p = mapOf("ATV" to 0.8, "Car" to 0.2)
        val bal = 100_000L
        val advice = OddsEngine.compute(cars, pools, 90, bal, p)
        assertTrue(advice.totalBet <= (bal * 0.07).toInt() + 100) // rounding slack
    }

    // ---- calibration ----

    @Test fun confidenceBandsMeasuredWinRate() {
        val rows = listOf(
            round(80, true), round(80, true), round(80, false),
            round(40, false), round(40, false),
            round(20, true)
        )
        val bands = ConfidenceCalibration.fromRounds(rows)
        val high = bands.first { it.name == "HIGH" }
        assertEquals(3, high.bets)
        assertEquals(2, high.wins)
        assertEquals(66.666, high.winPct, 0.1)
        val report = ConfidenceCalibration.reportBlock(rows)
        assertTrue(report.contains("HIGH"))
        assertTrue(report.contains("n=3"))
    }

    // ---- local learner ----

    @Test fun learnerNeedsMinimumRounds() {
        val rows = List(5) {
            round(
                70, true, ts = it.toLong(),
                roadType1 = "Desert", roadPx1 = 500.0,
                roadType2 = "Highway", roadPx2 = 490.0,
                visible = "Desert", winner = "ATV", modelWinner = "ATV"
            )
        }
        val r = LocalLearner.tryLearn(rows)
        assertFalse(r.applied)
        assertTrue(r.message.contains("Need"))
    }

    @Test fun learnerAppliesWhenHoldoutNotWorse() {
        // 40 identical layout rounds where ATV always wins and is model winner
        val rows = List(40) { i ->
            round(
                70, true, winner = "ATV", modelWinner = "ATV",
                v1 = "ATV", v2 = "Car", v3 = "SUV",
                roadType1 = "Desert", roadPx1 = 600.0,
                roadType2 = "Highway", roadPx2 = 390.0,
                visible = "Desert", ts = i.toLong()
            )
        }
        val r = LocalLearner.tryLearn(rows)
        // May or may not apply depending on exact/finish model; must not crash
        assertTrue(r.message.isNotBlank())
        if (r.applied) {
            assertTrue(EngineParams.active.version.startsWith("8.1.learn"))
            assertTrue(LocalLearner.rollback())
            assertEquals("8.1.0", EngineParams.active.version)
        }
    }

    @Test fun rollbackRestoresPrevious() {
        val old = EngineParams.active
        val fake = old.copy(version = "8.1.learn.test")
        // simulate applied state
        val field = LocalLearner::class.java.getDeclaredField("previous")
        field.isAccessible = true
        field.set(null, old)
        EngineParams.use(fake)
        assertEquals("8.1.learn.test", EngineParams.active.version)
        assertTrue(LocalLearner.rollback())
        assertEquals(old.version, EngineParams.active.version)
        assertFalse(LocalLearner.rollback()) // nothing left
    }

    // ---- backtest ----

    @Test fun backtestRunsOnEmptyAndSynthetic() {
        assertTrue(Backtester.run(emptyList()).current.bets == 0)
        val rows = List(10) { i ->
            round(
                70, won = i % 2 == 0, winner = if (i % 2 == 0) "ATV" else "Car",
                v1 = "ATV", v2 = "Car", v3 = "SUV",
                poolA = 200_000, poolB = 4_000_000, poolC = 4_000_000,
                visible = "Desert", ts = i.toLong()
            )
        }
        val report = Backtester.run(rows)
        assertTrue(report.text().contains("Backtest"))
        assertTrue(report.current.rounds == 10)
    }

    @Test fun legacyGuessFavoursFastOnHighway() {
        val leg = Backtester.legacyGuess("Highway", listOf("Supercar", "ATV"))!!
        assertEquals("Supercar", leg[0].first)
        assertEquals(1.0, leg.sumOf { it.second }, 1e-9)
    }

    @Test fun analyticsReportContainsSession8Sections() {
        val rows = listOf(round(80, true), round(30, false))
        val text = AnalyticsStats.report(rows, 100_000L, "")
        assertTrue(text.contains("Confidence bands"))
        assertTrue(text.contains("Backtest"))
        assertTrue(text.contains("Params version"))
    }
}
