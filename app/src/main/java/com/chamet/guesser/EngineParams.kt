package com.chamet.guesser

/**
 * Versioned engine parameters (Session 7).
 * Defaults match params.json / the user speed table. Session 8 may replace
 * them from a learned file; rollback restores [DEFAULT].
 *
 * Pure Kotlin — no Android classes.
 */
data class EngineParams(
    val version: String = "8.1.0",
    val simulations: Int = 2000,
    val defaultSeed: Long = 42L,
    val roads: List<String> = listOf(
        "Highway", "Expressway", "Dirt", "Bumpy", "Potholes", "Desert"
    ),
    val speeds: Map<String, Map<String, Int>> = DEFAULT_SPEEDS,
    val roadFamilies: Map<String, List<String>> = DEFAULT_FAMILIES,
    /** Empirical segment lengths (px) used as the length prior. */
    val lengthPriorPx: List<Pair<String, Double>> = DEFAULT_LENGTH_PRIOR,
    val trackWidthPx: Double = 990.0,
    val segmentCountMin: Int = 2,
    val segmentCountMax: Int = 3,
    /** Default left/mid/right multipliers (overridden by Prefs when learned). */
    val positionBias: Triple<Double, Double, Double> = Triple(1.0, 1.0, 1.0)
) {
    fun speedOf(car: String, road: String): Int = speeds[car]?.get(road) ?: 0

    fun familyOf(revealed: String): List<String> =
        roadFamilies[revealed] ?: roads

    fun lengthsForType(type: String): List<Double> =
        lengthPriorPx.filter { it.first.equals(type, true) }.map { it.second }
            .ifEmpty { listOf(trackWidthPx / 3.0) }

    companion object {
        val DEFAULT_SPEEDS: Map<String, Map<String, Int>> = mapOf(
            "Supercar" to mapOf(
                "Highway" to 320, "Expressway" to 280, "Dirt" to 100,
                "Bumpy" to 60, "Potholes" to 32, "Desert" to 40
            ),
            "Sports Car" to mapOf(
                "Highway" to 240, "Expressway" to 220, "Dirt" to 120,
                "Bumpy" to 66, "Potholes" to 36, "Desert" to 45
            ),
            "Car" to mapOf(
                "Highway" to 180, "Expressway" to 200, "Dirt" to 130,
                "Bumpy" to 75, "Potholes" to 45, "Desert" to 50
            ),
            "SUV" to mapOf(
                "Highway" to 143, "Expressway" to 180, "Dirt" to 134,
                "Bumpy" to 76, "Potholes" to 48, "Desert" to 57
            ),
            "Stock Car" to mapOf(
                "Highway" to 100, "Expressway" to 150, "Dirt" to 80,
                "Bumpy" to 80, "Potholes" to 60, "Desert" to 80
            ),
            "ORV" to mapOf(
                "Highway" to 112, "Expressway" to 140, "Dirt" to 92,
                "Bumpy" to 91, "Potholes" to 49, "Desert" to 70
            ),
            "Monster Truck" to mapOf(
                "Highway" to 99, "Expressway" to 120, "Dirt" to 66,
                "Bumpy" to 99, "Potholes" to 77, "Desert" to 55
            ),
            "Motorcycle" to mapOf(
                "Highway" to 89, "Expressway" to 110, "Dirt" to 81,
                "Bumpy" to 81, "Potholes" to 68, "Desert" to 63
            ),
            "ATV" to mapOf(
                "Highway" to 80, "Expressway" to 100, "Dirt" to 76,
                "Bumpy" to 72, "Potholes" to 72, "Desert" to 72
            )
        )

        val DEFAULT_FAMILIES: Map<String, List<String>> = mapOf(
            "Dirt" to listOf("Dirt", "Dirt", "Bumpy", "Potholes", "Bumpy", "Dirt"),
            "Bumpy" to listOf("Bumpy", "Bumpy", "Dirt", "Potholes", "Dirt", "Desert"),
            "Potholes" to listOf("Potholes", "Potholes", "Dirt", "Bumpy", "Dirt", "Desert"),
            "Desert" to listOf("Desert", "Desert", "Dirt", "Bumpy", "Potholes", "Desert"),
            "Highway" to listOf("Highway", "Highway", "Expressway", "Dirt", "Expressway", "Highway"),
            "Expressway" to listOf("Expressway", "Expressway", "Highway", "Dirt", "Highway", "Desert")
        )

        /** Measured layouts from the plan + a few neutral priors. */
        /** Measured layouts from the plan goldens only (no invented fillers). */
        val DEFAULT_LENGTH_PRIOR: List<Pair<String, Double>> = listOf(
            "Desert" to 870.0, "Highway" to 118.0,                 // 4:11
            "Highway" to 167.0, "Expressway" to 98.0, "Desert" to 722.0, // 4:14
            "Desert" to 98.0, "Bumpy" to 415.0, "Desert" to 475.0  // 4:16
        )

        val DEFAULT = EngineParams()

        @Volatile
        var active: EngineParams = DEFAULT
            private set

        fun use(params: EngineParams) {
            active = params
            SpeedDatabase.reloadFrom(params)
        }

        fun resetToDefault() {
            use(DEFAULT)
        }
    }
}

/**
 * Tiny deterministic PRNG (LCG) so sims are reproducible with a seed
 * and need no java.util.Random platform quirks.
 */
class SeededRng(seed: Long) {
    private var state = seed xor 0x5DEECE66DL
    fun nextLong(): Long {
        state = (state * 6364136223846793005L + 1L)
        return state
    }
    fun nextInt(bound: Int): Int {
        if (bound <= 0) return 0
        val v = (nextLong() ushr 1) % bound
        return if (v < 0) (v + bound).toInt() else v.toInt()
    }
    fun nextDouble(): Double {
        val v = (nextLong() ushr 11) and ((1L shl 53) - 1)
        return v / (1L shl 53).toDouble()
    }
}
