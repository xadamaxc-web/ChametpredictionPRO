package com.chamet.guesser

/**
 * Session 6 — resolve who won without requiring a manual tap.
 *
 * Priority (highest first):
 *  1. manual  — user tapped WON/LOST/NO BET (optional override)
 *  2. track   — first vehicle across the finish line (Session 5)
 *  3. balance — balance after > balance before implies a win on a backed car
 *  4. unknown — saved anyway so the next round is never blocked
 *
 * winnerSource is stored on every round: track | balance | manual | unknown
 * (screen image-match is reserved for when finish-screen crops exist).
 */
object AutoResult {

    const val SOURCE_MANUAL = "manual"
    const val SOURCE_TRACK = "track"
    const val SOURCE_BALANCE = "balance"
    const val SOURCE_SCREEN = "screen"
    const val SOURCE_UNKNOWN = "unknown"

    data class Resolution(
        val winner: String?,
        val won: Boolean?,                 // null = no-bet or unknown
        val winnerSource: String,
        val winnerPosition: Int? = null,   // 1..3 screen column if known
        val finishOrder: List<String> = emptyList()
    )

    /**
     * Resolve from available signals. [manualWinner]/[manualWon] win if provided.
     */
    fun resolve(
        manualWinner: String? = null,
        manualWon: Boolean? = null,
        noBet: Boolean = false,
        trackOrder: List<String> = emptyList(),
        trackWinner: String? = trackOrder.firstOrNull(),
        /** card / lane cars left→right for position lookup */
        positionCars: List<String> = emptyList(),
        balanceBefore: Long = 0,
        balanceAfter: Long = 0,
        /** cars we suggested a stake on */
        backedCars: List<String> = emptyList()
    ): Resolution {
        if (noBet) {
            return Resolution(
                winner = manualWinner ?: trackWinner,
                won = null,
                winnerSource = if (manualWinner != null) SOURCE_MANUAL
                else if (trackWinner != null) SOURCE_TRACK
                else SOURCE_UNKNOWN,
                winnerPosition = positionOf(manualWinner ?: trackWinner, positionCars),
                finishOrder = trackOrder
            )
        }

        // 1. Explicit manual settle
        if (manualWon != null) {
            val w = manualWinner ?: trackWinner
            return Resolution(
                winner = w,
                won = manualWon,
                winnerSource = SOURCE_MANUAL,
                winnerPosition = positionOf(w, positionCars),
                finishOrder = trackOrder.ifEmpty { listOfNotNull(w) }
            )
        }

        // 2. Track crossing order
        if (!trackWinner.isNullOrBlank()) {
            val won = when {
                backedCars.isEmpty() -> true   // observed only, treat as "winner known"
                else -> backedCars.any { it.equals(trackWinner, true) }
            }
            return Resolution(
                winner = trackWinner,
                won = if (backedCars.isEmpty()) null else won,
                winnerSource = SOURCE_TRACK,
                winnerPosition = positionOf(trackWinner, positionCars),
                finishOrder = trackOrder
            )
        }

        // 3. Balance backup: payout only if after > before
        if (balanceBefore > 0 && balanceAfter > balanceBefore && backedCars.isNotEmpty()) {
            // Can't know which car; leave winner null but mark as won via balance
            return Resolution(
                winner = null,
                won = true,
                winnerSource = SOURCE_BALANCE,
                winnerPosition = null,
                finishOrder = emptyList()
            )
        }
        if (balanceBefore > 0 && balanceAfter < balanceBefore && backedCars.isNotEmpty()) {
            return Resolution(
                winner = null,
                won = false,
                winnerSource = SOURCE_BALANCE,
                winnerPosition = null,
                finishOrder = emptyList()
            )
        }

        // 4. Unknown — still save the round
        return Resolution(
            winner = null,
            won = null,
            winnerSource = SOURCE_UNKNOWN,
            winnerPosition = null,
            finishOrder = emptyList()
        )
    }

    fun positionOf(winner: String?, positionCars: List<String>): Int? {
        if (winner.isNullOrBlank()) return null
        val i = positionCars.indexOfFirst { it.equals(winner, true) }
        return if (i >= 0) i + 1 else null
    }

    /**
     * Model accuracy over rounds that have both modelWinner and a real winner.
     * Returns (hits, known, pct).
     */
    fun modelAccuracy(rows: List<RoundEntity>): Triple<Int, Int, Double> {
        var hits = 0
        var known = 0
        for (r in rows) {
            val model = r.modelWinner ?: continue
            val real = r.winner ?: continue
            known++
            if (FinishTimeModel.modelHit(model, real)) hits++
        }
        val pct = if (known == 0) 0.0 else hits * 100.0 / known
        return Triple(hits, known, pct)
    }

    /** Auto-winner agreement vs manual (winnerSource == manual) over track-sourced rows. */
    fun trackAgreement(rows: List<RoundEntity>): Triple<Int, Int, Double> {
        // Rounds where we have both a track finish and a manual winner to compare
        // are not stored separately; use finishOrder vs winner when source is manual.
        var hits = 0
        var known = 0
        for (r in rows) {
            val order = r.finishOrder?.split("|")?.filter { it.isNotBlank() } ?: continue
            val trackWin = order.firstOrNull() ?: continue
            val real = r.winner ?: continue
            if (r.winnerSource != SOURCE_MANUAL) continue
            known++
            if (trackWin.equals(real, true)) hits++
        }
        val pct = if (known == 0) 0.0 else hits * 100.0 / known
        return Triple(hits, known, pct)
    }
}
