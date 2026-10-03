package com.chamet.guesser

/**
 * Session 4 — finish-screen winner hint (pure).
 *
 * Without a real finish-screen template, this module scores OCR text for
 * known vehicle names near "WIN" / "1st" markers. When templates exist,
 * swap the body of [matchFromText] / [matchFromPixels] only.
 */
object FinishScreenMatcher {

    private val WIN_MARKERS = listOf("win", "winner", "1st", "first", "champion")

    /**
     * @return best vehicle name found in text after a win marker, or null.
     */
    fun matchFromText(rawText: String?, knownCars: List<String>): String? {
        if (rawText.isNullOrBlank() || knownCars.isEmpty()) return null
        val lower = rawText.lowercase()
        val hasWin = WIN_MARKERS.any { lower.contains(it) }
        if (!hasWin) {
            // Still try a lone car name if only one matches
            val hits = knownCars.filter { lower.contains(it.lowercase()) }
            return hits.singleOrNull()
        }
        // Prefer the car name that appears closest after a win marker
        var best: String? = null
        var bestPos = Int.MAX_VALUE
        for (marker in WIN_MARKERS) {
            var idx = lower.indexOf(marker)
            while (idx >= 0) {
                for (car in knownCars) {
                    val cidx = lower.indexOf(car.lowercase(), idx)
                    if (cidx >= 0 && cidx < bestPos) {
                        bestPos = cidx
                        best = car
                    }
                }
                idx = lower.indexOf(marker, idx + 1)
            }
        }
        return best ?: knownCars.firstOrNull { lower.contains(it.lowercase()) }
    }

    /**
     * Pixel path reserved for template match. Returns null until templates ship.
     * Unverified without finish-screen screenshots.
     */
    fun matchFromPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        knownCars: List<String>
    ): String? {
        // No templates in repo — intentionally null (do not invent a winner).
        return null
    }
}
