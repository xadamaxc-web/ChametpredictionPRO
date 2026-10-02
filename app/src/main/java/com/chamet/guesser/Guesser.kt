package com.chamet.guesser

import kotlin.math.min

/**
 * Session 7 math engine — finish-time model.
 *
 *  - Layout **known**: exact T = Σ (px ÷ speed); rank by time (via [FinishTimeModel]).
 *  - Layout **hidden**: Monte Carlo over road types + length prior (~2000 sims).
 *    Outputs P(win) and P(top 2). Deterministic given [seed].
 *
 * Bet sizing stays in [OddsEngine]. No Android classes.
 */
object Guesser {

    data class RankedCar(
        val car: String,
        /** Higher is better for ranking UI; set to winProb (0..1). */
        val expectedScore: Double,
        val revealedSpeed: Int,
        val avgHiddenSpeed: Double,
        val winProb: Double = 0.0,
        val top2Prob: Double = 0.0,
        /** Mean finish time across sims (or exact time when layout known). */
        val meanTime: Double = 0.0
    )

    data class Result(
        val ranked: List<RankedCar>,
        val confidence: Int,
        val confidenceLabel: String,
        val paramsVersion: String = EngineParams.active.version,
        val seed: Long? = null,
        val simulations: Int = 0,
        val layoutKnown: Boolean = false
    )

    /**
     * Pre-race / post-race ranking.
     *
     * @param knownSegments when non-null and non-empty, uses exact finish times
     *        (race already started). Otherwise simulates hidden layout.
     * @param seed fixed seed → identical P(win); null uses params defaultSeed.
     */
    fun guess(
        revealedRoad: String,
        offeredCars: List<String>,
        positionBias: Map<String, Double> = emptyMap(),
        poolByCar: Map<String, Long> = emptyMap(),
        knownSegments: List<Pair<String, Double>>? = null,
        seed: Long? = null,
        simulations: Int? = null
    ): Result? {
        val cars = offeredCars.filter { it.isNotBlank() && it != "—" }
        if (cars.isEmpty()) return null

        if (!knownSegments.isNullOrEmpty()) {
            return exactGuess(cars, knownSegments, positionBias, poolByCar)
        }
        return simulateGuess(
            revealedRoad, cars, positionBias, poolByCar,
            seed ?: EngineParams.active.defaultSeed,
            simulations ?: EngineParams.active.simulations
        )
    }

    /** Exact order when the strip layout is known. */
    fun exactGuess(
        cars: List<String>,
        segments: List<Pair<String, Double>>,
        positionBias: Map<String, Double> = emptyMap(),
        poolByCar: Map<String, Long> = emptyMap()
    ): Result? {
        val model = FinishTimeModel.compute(cars, segments) ?: return null
        // Apply position bias as a soft time multiplier (bias > 1 → slightly faster)
        val adjusted = model.times.map { ct ->
            val bias = positionBias[ct.car] ?: 1.0
            val t = if (bias > 0) ct.time / bias else ct.time
            ct.copy(time = t)
        }.sortedBy { it.time }

        val ranked = adjusted.mapIndexed { i, ct ->
            RankedCar(
                car = ct.car,
                expectedScore = 1.0 / (i + 1.0), // rank-based placeholder
                revealedSpeed = SpeedDatabase.speedOf(ct.car, segments.first().first),
                avgHiddenSpeed = 0.0,
                winProb = if (i == 0) 1.0 else 0.0,
                top2Prob = if (i < 2) 1.0 else 0.0,
                meanTime = ct.time
            )
        }
        // Soften exact winProb for UI confidence only — still 100% model winner
        val top = ranked[0]
        val conf = confidenceOf(1.0, 1.0, oddsAgreement(top.car, ranked.map { it.car }, poolByCar))
        return Result(
            ranked = ranked,
            confidence = conf.coerceAtLeast(90),
            confidenceLabel = "HIGH",
            layoutKnown = true,
            simulations = 0
        )
    }

    /**
     * Hidden-layout Monte Carlo.
     * Each sim: pick segment count, types from family of visible road, lengths
     * from prior (normalised to track width), one segment forced to visible type.
     */
    fun simulateGuess(
        revealedRoad: String,
        cars: List<String>,
        positionBias: Map<String, Double> = emptyMap(),
        poolByCar: Map<String, Long> = emptyMap(),
        seed: Long = EngineParams.active.defaultSeed,
        simulations: Int = EngineParams.active.simulations
    ): Result? {
        if (cars.isEmpty() || simulations <= 0) return null
        val params = EngineParams.active
        val rng = SeededRng(seed)
        val family = params.familyOf(revealedRoad)
        val trackW = params.trackWidthPx

        val winCounts = DoubleArray(cars.size)
        val top2Counts = DoubleArray(cars.size)
        val timeSum = DoubleArray(cars.size)

        repeat(simulations) {
            val segments = sampleLayout(revealedRoad, family, trackW, params, rng)
            val times = cars.mapIndexed { idx, car ->
                var t = 0.0
                val bias = positionBias[car] ?: 1.0
                for ((road, px) in segments) {
                    val sp = params.speedOf(car, road).toDouble()
                    if (sp <= 0) return@mapIndexed idx to Double.POSITIVE_INFINITY
                    t += px / sp
                }
                if (bias > 0) t /= bias
                idx to t
            }.sortedBy { it.second }

            if (times.isEmpty() || times[0].second.isInfinite()) return@repeat
            val winnerIdx = times[0].first
            winCounts[winnerIdx] += 1.0
            for (k in 0 until min(2, times.size)) {
                top2Counts[times[k].first] += 1.0
            }
            for ((idx, t) in times) {
                if (t.isFinite()) timeSum[idx] += t
            }
        }

        val n = simulations.toDouble()
        val ranked = cars.mapIndexed { i, car ->
            val pWin = winCounts[i] / n
            RankedCar(
                car = car,
                expectedScore = pWin,
                revealedSpeed = params.speedOf(car, revealedRoad),
                avgHiddenSpeed = family.map { params.speedOf(car, it) }.average(),
                winProb = pWin,
                top2Prob = top2Counts[i] / n,
                meanTime = timeSum[i] / n
            )
        }.sortedByDescending { it.winProb }

        val first = ranked[0]
        val second = ranked.getOrNull(1)
        val gap = if (second != null && first.winProb > 0) {
            ((first.winProb - second.winProb) / first.winProb).coerceIn(0.0, 1.0)
        } else 1.0
        val sampleRel = min(simulations / 2000.0, 1.0)
        val conf = confidenceOf(gap, sampleRel, oddsAgreement(first.car, ranked.map { it.car }, poolByCar))
        val label = when {
            conf >= 75 -> "HIGH"
            conf >= 55 -> "MEDIUM"
            conf >= 35 -> "LOW"
            else -> "VERY LOW"
        }
        return Result(
            ranked = ranked,
            confidence = conf,
            confidenceLabel = label,
            seed = seed,
            simulations = simulations,
            layoutKnown = false
        )
    }

    /**
     * Build one random layout: 2–3 segments, at least one is the visible road,
     * lengths drawn from the prior and scaled to [trackW].
     */
    fun sampleLayout(
        revealedRoad: String,
        family: List<String>,
        trackW: Double,
        params: EngineParams,
        rng: SeededRng
    ): List<Pair<String, Double>> {
        val nSeg = params.segmentCountMin +
            rng.nextInt((params.segmentCountMax - params.segmentCountMin + 1).coerceAtLeast(1))
        val types = MutableList(nSeg) { family[rng.nextInt(family.size)] }
        // Guarantee visible road appears
        if (types.none { it.equals(revealedRoad, true) }) {
            types[rng.nextInt(nSeg)] = revealedRoad
        }
        val raw = types.map { t ->
            val choices = params.lengthsForType(t)
            choices[rng.nextInt(choices.size)]
        }
        val sum = raw.sum().coerceAtLeast(1.0)
        return types.zip(raw.map { it * trackW / sum })
    }

    fun oddsAgreement(topCar: String, cars: List<String>, poolByCar: Map<String, Long>): Double? {
        val pools = cars.mapNotNull { c -> poolByCar[c]?.takeIf { it > 0 }?.let { c to it } }
        if (pools.size < 2 || pools.none { it.first == topCar }) return null
        val order = pools.sortedByDescending { it.second }.map { it.first }
        val idx = order.indexOf(topCar)
        return 1.0 - idx.toDouble() / (order.size - 1)
    }

    fun confidenceOf(scoreGap: Double, sampleReliability: Double, oddsAgreement: Double?): Int {
        val raw = if (oddsAgreement != null)
            scoreGap * 0.55 + oddsAgreement * 0.30 + sampleReliability * 0.15
        else
            (scoreGap * 0.55 + sampleReliability * 0.15) / 0.70
        return (raw * 100).toInt().coerceIn(0, 100)
    }
}
