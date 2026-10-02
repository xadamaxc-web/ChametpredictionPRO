package com.chamet.guesser

/**
 * Session 8 — confidence bands calibrated on logged bet rounds.
 * Band is taken from the stored confidence at log time; measured win rate
 * is wins/bets inside that band (no-bet rounds excluded).
 */
object ConfidenceCalibration {

    data class Band(
        val name: String,
        val minConf: Int,
        val maxConf: Int,
        val bets: Int,
        val wins: Int
    ) {
        val winPct: Double get() = if (bets == 0) 0.0 else wins * 100.0 / bets
        fun line(): String =
            "${name.padEnd(10)} conf $minConf–$maxConf  n=$bets  win ${"%.1f".format(winPct)}%"
    }

    val DEFAULT_BANDS = listOf(
        "HIGH" to (75 to 100),
        "MEDIUM" to (55 to 74),
        "LOW" to (35 to 54),
        "VERY LOW" to (0 to 34)
    )

    fun fromRounds(rows: List<RoundEntity>): List<Band> {
        val bets = rows.filter { it.won != null }
        return DEFAULT_BANDS.map { (name, range) ->
            val (lo, hi) = range
            val subset = bets.filter { it.confidence in lo..hi }
            Band(name, lo, hi, subset.size, subset.count { it.won == true })
        }
    }

    fun reportBlock(rows: List<RoundEntity>): String {
        val bands = fromRounds(rows)
        val sb = StringBuilder()
        sb.appendLine("=== Confidence bands (measured) ===")
        bands.forEach { sb.appendLine(it.line()) }
        val withData = bands.filter { it.bets > 0 }
        if (withData.isEmpty()) {
            sb.appendLine("(no bet rounds with confidence yet)")
        }
        return sb.toString().trimEnd()
    }
}
