package com.chamet.guesser

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Odds / EV / dynamic bet-split math.
 *
 * odds = totalPool / carPool
 * EV   = odds * winProbability   (winProbability estimated from Guesser score share)
 */
object OddsEngine {

    data class CarOdds(
        val car: String,
        val pool: Long,
        val odds: Double,
        val ev: Double,
        val suggestedBet: Int
    )

    data class SplitAdvice(
        val cars: List<CarOdds>,
        val totalBet: Int,
        val bet1: Int,
        val car1: String?,
        val bet2: Int,
        val car2: String?,
        val rank1: Int,   // math rank (1-3) of car1, 0 if none
        val rank2: Int,   // math rank (1-3) of car2, 0 if none
        val warning: String?,
        val topEvIndex: Int
    )

    /** Pool numbers like 12,345 or 1,234,567 found in ONE OCR line, in reading order. */
    fun findPools(lineText: String): List<Long> {
        val regex = Regex("""\d{1,3}(?:,\d{3})+""")
        return regex.findAll(lineText)
            .mapNotNull { it.value.replace(",", "").toLongOrNull() }
            .filter { it >= 1000 } // ignore tiny numbers
            .toList()
    }

    /**
     * The single bet adviser.
     * @param cars ordered by math rank: index0 = rank 1 (best) ... index2 = rank 3
     * @param poolByCar car name -> its pool (matched by screen position, never by size)
     * @param confidence 0-100 from Guesser
     * @param balance current diamond balance
     */
    fun compute(
        cars: List<String>,
        poolByCar: Map<String, Long>,
        confidence: Int,
        balance: Long,
        winProb: Map<String, Double> = emptyMap(),   // from Guesser scores; missing = equal chance
        /** Stake cap as percent of balance (Settings); default 7. */
        stakeCapPercent: Int = 7
    ): SplitAdvice {
        if (cars.isEmpty()) {
            return SplitAdvice(emptyList(), 0, 0, null, 0, null, 0, 0, "No cars", -1)
        }

        val paddedPools = cars.map { poolByCar[it] ?: 0L }
        val totalPool = paddedPools.sum().coerceAtLeast(1L)

        // Win chance comes from the Guesser score share. Confidence only scales the stake below.
        val raw = cars.map { winProb[it]?.takeIf { p -> p > 0 } ?: (1.0 / cars.size) }
        val rawSum = raw.sum().coerceAtLeast(0.0001)
        val weights = raw.map { it / rawSum }

        val carOdds = cars.mapIndexed { i, car ->
            val pool = paddedPools[i]
            val odds = if (pool > 0) totalPool.toDouble() / pool else 0.0
            val pWin = weights[i]
            val ev = odds * pWin
            CarOdds(car, pool, odds, ev, 0)
        }

        val capPct = stakeCapPercent.coerceIn(1, 20) / 100.0
        var cap = (balance * capPct).toLong()
        var warning: String? = null

        if (confidence < 40) {
            cap = (cap * 0.5).toLong()
            warning = "Low conf — half stake"
        }

        val maxEv = carOdds.maxOfOrNull { it.ev } ?: 0.0
        if (maxEv < 1.0 && carOdds.any { it.pool > 0 }) {
            warning = (warning?.let { "$it · " } ?: "") + "All EV < 1 — consider skip"
        }

        val sortedByEv = carOdds.withIndex().sortedByDescending { it.value.ev }
        val top = sortedByEv.getOrNull(0)
        val second = sortedByEv.getOrNull(1)

        var bet1 = 0
        var bet2 = 0
        var car1: String? = null
        var car2: String? = null
        var rank1 = 0
        var rank2 = 0
        val topEvIndex = top?.index ?: -1

        // Session 8: skip when every EV < 1; never exceed cap after rounding
        if (top != null && cap > 0 && top.value.ev >= 1.0) {
            car1 = top.value.car
            rank1 = top.index + 1
            if (second != null &&
                top.value.ev <= second.value.ev * 1.5 &&
                second.value.ev >= 1.0
            ) {
                val ratio = top.value.ev / (top.value.ev + second.value.ev)
                bet1 = round100((cap * ratio).toInt())
                bet2 = round100((cap - bet1).toInt())
                // Clamp so bet1+bet2 never exceeds cap after rounding
                if (bet1 + bet2 > cap) {
                    bet2 = round100((cap - bet1).toInt().coerceAtLeast(0))
                    if (bet1 + bet2 > cap) bet1 = round100(cap.toInt())
                    if (bet1 + bet2 > cap) { bet1 = (cap / 100 * 100).toInt(); bet2 = 0 }
                }
                car2 = second.value.car
                rank2 = second.index + 1
            } else {
                bet1 = round100(cap.toInt())
                if (bet1 > cap) bet1 = (cap / 100 * 100).toInt()
            }
        } else if (maxEv < 1.0) {
            warning = (warning?.let { "$it · " } ?: "") + "Skip: all EV < 1"
        }

        // Write suggestedBet back onto CarOdds list (by car name)
        val betMap = mutableMapOf<String, Int>()
        if (car1 != null) betMap[car1] = bet1
        if (car2 != null) betMap[car2] = bet2

        val withBets = carOdds.map { c ->
            c.copy(suggestedBet = betMap[c.car] ?: 0)
        }

        return SplitAdvice(
            cars = withBets,
            totalBet = bet1 + bet2,
            bet1 = bet1,
            car1 = car1,
            bet2 = bet2,
            car2 = car2,
            rank1 = rank1,
            rank2 = rank2,
            warning = warning,
            topEvIndex = topEvIndex
        )
    }

    /**
     * Balance change for a finished round, given the bets we placed (bet1 on car1, bet2 on car2)
     * and the car that really won. Only the bet on the winner pays; the other bet is lost.
     * winnerCar == null (lost / unknown) means nothing pays.
     */
    fun net(advice: SplitAdvice?, winnerCar: String?): Long {
        if (advice == null) return 0L
        val staked = (advice.bet1 + advice.bet2).toLong()
        val payout = when {
            winnerCar == null -> 0L
            winnerCar == advice.car1 && advice.bet1 > 0 ->
                (advice.bet1 * (advice.cars.firstOrNull { it.car == advice.car1 }?.odds ?: 0.0)).toLong()
            winnerCar == advice.car2 && advice.bet2 > 0 ->
                (advice.bet2 * (advice.cars.firstOrNull { it.car == advice.car2 }?.odds ?: 0.0)).toLong()
            else -> 0L
        }
        return payout - staked
    }

    /** True when we placed a (suggested) bet on this car. */
    fun isBacked(advice: SplitAdvice?, car: String?): Boolean =
        advice != null && car != null &&
            ((car == advice.car1 && advice.bet1 > 0) || (car == advice.car2 && advice.bet2 > 0))

    fun ordinal(rank: Int): String = when (rank) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "—" }

    private fun round100(v: Int): Int {
        if (v <= 0) return 0
        return ((v + 50) / 100) * 100
    }

    fun formatOdds(odds: Double): String =
        if (odds <= 0) "—" else String.format("%.1f×", odds)

    fun formatEv(ev: Double): String =
        if (ev <= 0) "—" else String.format("EV %.2f", ev)

    fun formatBet(bet: Int): String =
        if (bet <= 0) "" else "%,d 💎".format(bet)
}
