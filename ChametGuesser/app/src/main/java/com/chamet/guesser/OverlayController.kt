package com.chamet.guesser

/**
 * Session 9 — light overlay controller helpers (pure, unit-tested).
 * One colour / label per round state; one-glance suggestion line.
 */
object OverlayController {

    enum class Phase {
        BETTING, CLOSED, RACE, FINISH, SAVED
    }

    /** Display colour (ARGB hex without alpha prefix for UI). */
    fun phaseColor(phase: Phase): String = when (phase) {
        Phase.BETTING -> "#2A9D8F"   // teal
        Phase.CLOSED -> "#E9C46A"    // gold
        Phase.RACE -> "#F4A261"      // orange
        Phase.FINISH -> "#E76F51"    // coral
        Phase.SAVED -> "#6A994E"     // green
    }

    fun phaseLabel(phase: Phase): String = when (phase) {
        Phase.BETTING -> "● BETTING"
        Phase.CLOSED -> "● CLOSED"
        Phase.RACE -> "● RACE"
        Phase.FINISH -> "● FINISH"
        Phase.SAVED -> "● SAVED"
    }

    /**
     * Infer phase from flags already on the overlay.
     */
    fun inferPhase(
        raceLocked: Boolean,
        resultRecorded: Boolean,
        hasStripLayout: Boolean,
        captureStage: String?
    ): Phase = when {
        resultRecorded || captureStage == "saved" -> Phase.SAVED
        captureStage == "finish" || (raceLocked && hasStripLayout && captureStage == "finish") -> Phase.FINISH
        raceLocked && hasStripLayout -> Phase.RACE
        raceLocked -> Phase.CLOSED
        else -> Phase.BETTING
    }

    /**
     * One-glance card text: pick · band · stake.
     * Observe-only forces stake display to 0.
     */
    fun glanceLine(
        car1: String?,
        bet1: Int,
        confidenceLabel: String,
        confidence: Int,
        observeOnly: Boolean,
        modelOrder: List<String> = emptyList(),
        layoutKnown: Boolean = false
    ): String {
        val band = confidenceLabel.ifBlank {
            when {
                confidence >= 75 -> "HIGH"
                confidence >= 55 -> "MEDIUM"
                confidence >= 35 -> "LOW"
                else -> "VERY LOW"
            }
        }
        val stake = if (observeOnly) 0 else bet1
        val pick = when {
            car1.isNullOrBlank() -> "—"
            observeOnly -> "$car1 (observe)"
            else -> car1
        }
        val main = "Pick $pick · $band · ${"%,d".format(stake)} 💎"
        if (layoutKnown && modelOrder.isNotEmpty()) {
            return main + "\nModel: " + modelOrder.joinToString(" › ")
        }
        return main
    }

    /** Effective confidence threshold for staking (prefs). */
    fun stakeAllowed(confidence: Int, minConfidence: Int, observeOnly: Boolean): Boolean {
        if (observeOnly) return false
        return confidence >= minConfidence
    }

    /**
     * Apply observe-only / min-confidence by zeroing bets on a SplitAdvice copy.
     */
    fun filterAdvice(
        advice: OddsEngine.SplitAdvice?,
        confidence: Int,
        minConfidence: Int,
        observeOnly: Boolean
    ): OddsEngine.SplitAdvice? {
        if (advice == null) return null
        if (!stakeAllowed(confidence, minConfidence, observeOnly)) {
            return advice.copy(
                totalBet = 0, bet1 = 0, bet2 = 0, car1 = advice.car1, car2 = null,
                warning = if (observeOnly) "Observe-only — no stake"
                else "Below conf threshold ($minConfidence%) — no stake",
                cars = advice.cars.map { it.copy(suggestedBet = 0) }
            )
        }
        return advice
    }
}
