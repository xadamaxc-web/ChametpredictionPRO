package com.chamet.guesser

/**
 * Pure analytics over logged rounds (no Android calls, so it is unit-tested).
 *
 * Round kinds after Session 4:
 *  - bet round:    won != null   (WON or LOST was tapped)
 *  - no-bet round: won == null   (NO BET, or an old round that was never settled)
 *
 * Two different "where did the winner come from" numbers:
 *  - winner RANK     = place of the winning car in OUR ranking (v1 / v2 / v3)
 *  - winner POSITION = screen column of the winning car (left / mid / right)
 */
object AnalyticsStats {

    const val BIAS_MIN_ROUNDS = 30
    const val BIAS_THRESHOLD = 0.40

    data class Overview(
        val total: Int,
        val bet: Int,
        val noBet: Int,
        val wins: Int,
        val losses: Int,
        val winPct: Double,        // wins / bet rounds (no-bet rounds are not counted)
        val currentStreak: Int,
        val longestStreak: Int
    )

    /** Counts for slot 1..3 (index 0..2) over rounds where the winner is known. */
    data class Dist(val known: Int, val counts: List<Int>) {
        fun pct(i: Int): Double = if (known == 0) 0.0 else counts[i] * 100.0 / known
    }

    /** The one road picked before the race (R1/R2/R3 hold at most one real value per round). */
    fun roadOf(r: RoundEntity): String =
        listOf(r.r1, r.r2, r.r3).firstOrNull { !it.isNullOrBlank() && it != "???" } ?: "???"

    /** 1..3 = place of the winner in v1/v2/v3, null when the winner is unknown. */
    fun winnerRank(r: RoundEntity): Int? {
        val w = r.winner ?: return null
        val i = listOf(r.v1, r.v2, r.v3).indexOf(w)
        return if (i >= 0) i + 1 else null
    }

    /** 1..3 = screen column of the winner (left/mid/right), null when unknown. */
    fun winnerPosition(r: RoundEntity): Int? {
        r.winnerPosition?.takeIf { it in 1..3 }?.let { return it }
        val w = r.winner ?: return null
        val i = listOf(r.carPosition1, r.carPosition2, r.carPosition3).indexOf(w)
        return if (i >= 0) i + 1 else null
    }

    fun overview(rows: List<RoundEntity>): Overview {
        val bets = rows.filter { it.won != null }.sortedBy { it.timestamp }
        val wins = bets.count { it.won == true }
        val losses = bets.count { it.won == false }
        var run = 0
        var longest = 0
        for (r in bets) {
            if (r.won == true) { run++; longest = maxOf(longest, run) } else run = 0
        }
        var cur = 0
        for (r in bets.asReversed()) { if (r.won == true) cur++ else break }
        val winPct = if (bets.isNotEmpty()) wins * 100.0 / bets.size else 0.0
        return Overview(rows.size, bets.size, rows.size - bets.size, wins, losses, winPct, cur, longest)
    }

    private fun dist(values: List<Int?>): Dist {
        val known = values.filterNotNull()
        return Dist(known.size, (1..3).map { p -> known.count { it == p } })
    }

    fun rankDist(rows: List<RoundEntity>): Dist = dist(rows.map { winnerRank(it) })
    fun positionDist(rows: List<RoundEntity>): Dist = dist(rows.map { winnerPosition(it) })

    /**
     * Position-bias multipliers (P1, P2, P3) from the screen-position distribution.
     * Returns null while there are fewer than BIAS_MIN_ROUNDS rounds with a known winner position.
     */
    fun biasMultipliers(rows: List<RoundEntity>): Triple<Double, Double, Double>? {
        val d = positionDist(rows)
        if (d.known < BIAS_MIN_ROUNDS) return null
        val p1 = d.counts[0].toDouble() / d.known
        val p3 = d.counts[2].toDouble() / d.known
        return when {
            p3 > BIAS_THRESHOLD -> Triple(0.85, 1.0, 1.25)
            p1 > BIAS_THRESHOLD -> Triple(1.25, 1.0, 0.85)
            else -> Triple(1.0, 1.0, 1.0)
        }
    }

    private fun fmt(v: Double) = "%.1f".format(v)

    fun report(rows: List<RoundEntity>, currentBalance: Long, biasNote: String): String {
        if (rows.isEmpty()) return "No rounds logged yet."
        val o = overview(rows)
        val sb = StringBuilder()

        sb.appendLine("=== Overview ===")
        sb.appendLine("Total rounds:       ${o.total}")
        sb.appendLine("Bet rounds:         ${o.bet}")
        sb.appendLine("No-bet rounds:      ${o.noBet}")
        sb.appendLine("Wins:               ${o.wins} (${fmt(o.winPct)}% of bet rounds)")
        sb.appendLine("Losses:             ${o.losses}")
        sb.appendLine("Current streak:     ${o.currentStreak} wins")
        sb.appendLine("Longest win streak: ${o.longestStreak}")
        sb.appendLine()

        val rank = rankDist(rows)
        sb.appendLine("=== Winner rank (our ranking) ===")
        sb.appendLine("Rounds with a known winner: ${rank.known}")
        for (i in 0..2) {
            sb.appendLine("${OddsEngine.ordinal(i + 1)} pick won: ${rank.counts[i]} (${fmt(rank.pct(i))}%)")
        }
        sb.appendLine()

        val pos = positionDist(rows)
        val names = listOf("Left (P1)", "Mid  (P2)", "Right (P3)")
        sb.appendLine("=== Winner screen position ===")
        sb.appendLine("Rounds with a known winner: ${pos.known}")
        for (i in 0..2) {
            val mark = if (pos.known >= BIAS_MIN_ROUNDS && pos.pct(i) > BIAS_THRESHOLD * 100) "  ← bias?" else ""
            sb.appendLine("${names[i]} won: ${pos.counts[i]} (${fmt(pos.pct(i))}%)$mark")
        }
        if (pos.known < BIAS_MIN_ROUNDS) {
            sb.appendLine("Bias needs $BIAS_MIN_ROUNDS rounds with a known winner (have ${pos.known}).")
        }
        sb.appendLine("Active multipliers: ${if (biasNote.isBlank()) "none (1.00 each)" else biasNote}")
        sb.appendLine()

        val bets = rows.filter { it.won != null }
        sb.appendLine("=== By revealed road (bet rounds) ===")
        bets.groupBy { roadOf(it) }.toSortedMap().forEach { (road, list) ->
            val w = list.count { it.won == true }
            sb.appendLine("${road.padEnd(12)} ${list.size} rounds  $w wins  ${fmt(w * 100.0 / list.size)}%")
        }
        sb.appendLine()

        sb.appendLine("=== By car (when ranked 1st, bet rounds) ===")
        bets.groupBy { it.v1 }.toSortedMap().forEach { (car, list) ->
            val w = list.count { it.won == true }
            sb.appendLine("${car.padEnd(12)} ${list.size} rounds  $w wins  ${fmt(w * 100.0 / list.size)}%")
        }
        sb.appendLine()

        // Session 6 — model accuracy (layout known) vs pick win rate (already above)
        val (mHits, mKnown, mPct) = AutoResult.modelAccuracy(rows)
        sb.appendLine("=== Model accuracy (layout known) ===")
        sb.appendLine("Rounds with model + real winner: $mKnown")
        sb.appendLine("Model hits:           $mHits (${fmt(mPct)}%)")
        val bySrc = rows.mapNotNull { it.winnerSource }.groupingBy { it }.eachCount()
        if (bySrc.isNotEmpty()) {
            sb.appendLine("Winner sources:       " + bySrc.entries.joinToString { "${it.key}=${it.value}" })
        }
        sb.appendLine("Params version:       ${EngineParams.active.version}")
        sb.appendLine()

        // Session 8 — calibrated confidence bands + backtest
        sb.appendLine(ConfidenceCalibration.reportBlock(rows))
        sb.appendLine()
        sb.appendLine(Backtester.reportBlock(rows))
        sb.appendLine()

        sb.appendLine("=== Balance ===")
        val bals = rows.map { it.balanceAfterPayout }.filter { it > 0 }
        val start = rows.minByOrNull { it.timestamp }?.balanceBeforeBet ?: 0L
        val high = bals.maxOrNull() ?: currentBalance
        val low = bals.minOrNull() ?: currentBalance
        val delta = if (start > 0) (currentBalance - start) * 100.0 / start else 0.0
        sb.appendLine("Starting:  ${"%,d".format(start)}")
        sb.appendLine("Current:   ${"%,d".format(currentBalance)}  (${"%+.1f".format(delta)}%)")
        sb.appendLine("Highest:   ${"%,d".format(high)}")
        sb.appendLine("Lowest:    ${"%,d".format(low)}")
        sb.appendLine()
        sb.appendLine(Phase1Gates.evaluate(rows).text())
        return sb.toString()
    }
}
