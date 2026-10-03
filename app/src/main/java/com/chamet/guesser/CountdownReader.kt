package com.chamet.guesser

/**
 * Session 2 — read the on-card countdown ("27s", "27 s", "6S") from OCR text
 * taken from a small crop (fractions of screen, not fixed pixels).
 *
 * Pure: no Android dependency. Callers pass OCR text from the crop region.
 */
object CountdownReader {

    /** Default crop as fractions of full screenshot (left, top, right, bottom). */
    val DEFAULT_CROP_FRACTIONS = floatArrayOf(0.15f, 0.35f, 0.85f, 0.55f)

    private val PATTERN = Regex(
        """(?<!\d)(\d{1,2})\s*[sS](?!\w)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Parse the first plausible countdown value (0–60) from free text.
     * Returns null when nothing looks like a race countdown.
     */
    fun parseSeconds(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        var best: Int? = null
        for (m in PATTERN.findAll(text)) {
            val v = m.groupValues[1].toIntOrNull() ?: continue
            if (v in 0..60) {
                // Prefer smaller values when several match (card timers, not pool sizes)
                if (best == null || v < best) best = v
            }
        }
        return best
    }

    /**
     * Map remaining seconds to a suggested machine event.
     * - ≥ 16 → still betting / early closed
     * - 7–15 → race window (plan: capture around 15 s left)
     * - 1–6 → finish window (plan: ~6 s left)
     * - 0 → finished
     */
    fun suggestedPhase(secondsLeft: Int?): RoundStateMachine.Phase? {
        if (secondsLeft == null) return null
        return when {
            secondsLeft <= 0 -> RoundStateMachine.Phase.FINISH
            secondsLeft <= 6 -> RoundStateMachine.Phase.FINISH
            secondsLeft <= 15 -> RoundStateMachine.Phase.RACE
            secondsLeft <= 30 -> RoundStateMachine.Phase.CLOSED
            else -> RoundStateMachine.Phase.BETTING
        }
    }

    data class CropBox(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int
    )

    /** Convert fractions to pixel crop on a bitmap of [w]×[h]. */
    fun cropBox(
        w: Int,
        h: Int,
        fractions: FloatArray = DEFAULT_CROP_FRACTIONS
    ): CropBox {
        val l = (w * fractions[0]).toInt().coerceIn(0, w - 1)
        val t = (h * fractions[1]).toInt().coerceIn(0, h - 1)
        val r = (w * fractions[2]).toInt().coerceIn(l + 1, w)
        val b = (h * fractions[3]).toInt().coerceIn(t + 1, h)
        return CropBox(l, t, r - l, b - t)
    }
}
