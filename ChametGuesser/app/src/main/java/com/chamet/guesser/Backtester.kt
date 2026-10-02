package com.chamet.guesser

/**
 * Session 8 — replay logged rounds through the current engine vs a legacy
 * 8.0.1-style score (equal-third speed sum), comparing win rate and profit.
 *
 * Pure: uses only fields already on [RoundEntity] (cars, pools, winner, balance).
 */
object Backtester {

    data class EngineStats(
        val name: String,
        val rounds: Int,
        val bets: Int,
        val wins: Int,
        val profit: Long
    ) {
        val winPct: Double get() = if (bets == 0) 0.0 else wins * 100.0 / bets
        fun line(): String =
            "${name.padEnd(12)} bets=$bets  wins=$wins (${"%.1f".format(winPct)}%)  profit=${"%,d".format(profit)}"
    }

    data class Report(
        val current: EngineStats,
        val legacy: EngineStats,
        val currentBeatsWinRate: Boolean,
        val currentBeatsProfit: Boolean
    ) {
        fun text(): String = buildString {
            appendLine("=== Backtest (replay logged rounds) ===")
            appendLine(current.line())
            appendLine(legacy.line())
            appendLine(
                when {
                    currentBeatsWinRate && currentBeatsProfit ->
                        "Current engine beats 8.0.1 on win rate and profit."
                    currentBeatsWinRate ->
                        "Current engine beats 8.0.1 on win rate only."
                    currentBeatsProfit ->
                        "Current engine beats 8.0.1 on profit only."
                    else ->
                        "Current engine does not beat 8.0.1 yet (need more / better rounds)."
                }
            )
        }
    }

    /**
     * Replay each bet round. Uses stored pools + cars; winner from the log.
     * Stake sizing uses the same OddsEngine rules at a fixed reference balance
     * when balanceBeforeBet is 0.
     */
    fun run(rows: List<RoundEntity>, seed: Long = 42L): Report {
        val betRows = rows.filter { it.won != null && !it.v1.isNullOrBlank() }
        var curWins = 0
        var curBets = 0
        var curProfit = 0L
        var legWins = 0
        var legBets = 0
        var legProfit = 0L

        for (r in betRows) {
            val cars = listOfNotNull(r.v1, r.v2, r.v3).filter { it.isNotBlank() }
            if (cars.size < 2) continue
            val pools = mapOf(
                cars[0] to r.poolA,
                cars.getOrElse(1) { "" } to r.poolB,
                cars.getOrElse(2) { "" } to r.poolC
            ).filterKeys { it.isNotBlank() }
            val balance = r.balanceBeforeBet.takeIf { it > 0 } ?: 100_000L
            val road = AnalyticsStats.roadOf(r).takeIf { RoadMatcher.isKnown(it) } ?: "Desert"
            val winner = r.winner

            // Current engine
            val segs = LocalLearner.segmentsOf(r).takeIf { it.size >= 2 }
            val curGuess = Guesser.guess(
                revealedRoad = road,
                offeredCars = cars,
                poolByCar = pools,
                knownSegments = segs,
                seed = seed
            )
            if (curGuess != null) {
                val winProb = curGuess.ranked.associate { it.car to it.winProb }
                val advice = OddsEngine.compute(
                    curGuess.ranked.map { it.car }, pools, curGuess.confidence, balance, winProb
                )
                if (advice.totalBet > 0) {
                    curBets++
                    val net = OddsEngine.net(advice, winner)
                    curProfit += net
                    if (winner != null && OddsEngine.isBacked(advice, winner)) curWins++
                }
            }

            // Legacy 8.0.1-style: speed×length equal thirds score share
            val leg = legacyGuess(road, cars)
            if (leg != null) {
                val winProb = leg.associate { it.first to it.second }
                val ordered = leg.map { it.first }
                val advice = OddsEngine.compute(ordered, pools, 50, balance, winProb)
                if (advice.totalBet > 0) {
                    legBets++
                    val net = OddsEngine.net(advice, winner)
                    legProfit += net
                    if (winner != null && OddsEngine.isBacked(advice, winner)) legWins++
                }
            }
        }

        val current = EngineStats("current", betRows.size, curBets, curWins, curProfit)
        val legacy = EngineStats("8.0.1-style", betRows.size, legBets, legWins, legProfit)
        return Report(
            current = current,
            legacy = legacy,
            currentBeatsWinRate = current.winPct >= legacy.winPct && current.bets > 0,
            currentBeatsProfit = current.profit >= legacy.profit && current.bets > 0
        )
    }

    /**
     * Old Guesser score: sum of speed on revealed + two "equal third" hidden
     * family draws → share of total as winProb.
     */
    fun legacyGuess(revealedRoad: String, cars: List<String>): List<Pair<String, Double>>? {
        if (cars.isEmpty()) return null
        val family = SpeedDatabase.ROAD_FAMILIES[revealedRoad] ?: SpeedDatabase.ROADS
        val scores = cars.map { car ->
            val revealed = SpeedDatabase.speedOf(car, revealedRoad).toDouble()
            var total = 0.0
            var n = 0
            for (a in family) for (b in family) {
                total += revealed * 3 + SpeedDatabase.speedOf(car, a) * 3.0 +
                    SpeedDatabase.speedOf(car, b) * 3.0
                n++
            }
            car to (if (n == 0) 0.0 else total / n)
        }
        val sum = scores.sumOf { it.second }.coerceAtLeast(1e-9)
        return scores
            .map { it.first to it.second / sum }
            .sortedByDescending { it.second }
    }

    fun reportBlock(rows: List<RoundEntity>): String = run(rows).text().trimEnd()
}
