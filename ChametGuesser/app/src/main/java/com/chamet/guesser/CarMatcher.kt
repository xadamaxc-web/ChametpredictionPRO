package com.chamet.guesser

/**
 * Matches OCR output (fuzzy) to the 9 known car names.
 * Handles common OCR misreads: "Suv" vs "SUV", "MonsterTruck", "Sports Car", etc.
 */
object CarMatcher {

    // Canonical names in the app (must match SpeedDatabase keys exactly)
    val KNOWN_CARS = listOf(
        "Supercar", "Sports Car", "Car", "SUV",
        "Stock Car", "ORV", "Monster Truck", "Motorcycle", "ATV"
    )

    // Aliases OCR might produce -> canonical name
    private val ALIASES: Map<String, String> = mapOf(
        "supercar" to "Supercar",
        "super car" to "Supercar",
        "super-car" to "Supercar",
        "sports car" to "Sports Car",
        "sportscar" to "Sports Car",
        "sports-car" to "Sports Car",
        "sport car" to "Sports Car",
        "car" to "Car",
        "suv" to "SUV",
        "s u v" to "SUV",
        "stock car" to "Stock Car",
        "stockcar" to "Stock Car",
        "stock-car" to "Stock Car",
        "orv" to "ORV",
        "o r v" to "ORV",
        "monster truck" to "Monster Truck",
        "monstertruck" to "Monster Truck",
        "monster-truck" to "Monster Truck",
        "monster" to "Monster Truck",
        "motorcycle" to "Motorcycle",
        "motor cycle" to "Motorcycle",
        "moto" to "Motorcycle",
        "bike" to "Motorcycle",
        "atv" to "ATV",
        "a t v" to "ATV"
    )

    /**
     * Given a list of OCR text lines (or blocks), return a distinct list of
     * matched car names, ordered by appearance.
     */
    fun matchCars(ocrLines: List<String>): List<String> {
        val found = LinkedHashSet<String>()

        for (raw in ocrLines) {
            val cleaned = raw.lowercase()
                .replace("\n", " ")
                .replace(Regex("[^a-z ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (cleaned.isEmpty()) continue

            val exactMatch = ALIASES[cleaned]
            if (exactMatch != null) {
                found.add(exactMatch)
                continue
            }

            val sortedAliases = ALIASES.keys.sortedByDescending { it.length }
            var matched: String? = null
            for (alias in sortedAliases) {
                if (cleaned.contains(alias)) {
                    matched = ALIASES[alias]
                    break
                }
            }
            if (matched != null) {
                found.add(matched)
            }
        }

        return found.toList().take(3)
    }

    /**
     * Simple edit-distance fallback for near-miss OCR strings.
     */
    fun fuzzyMatch(input: String, threshold: Int = 2): String? {
        val cleaned = input.lowercase().replace(Regex("[^a-z]"), "")
        var best: String? = null
        var bestDist = Int.MAX_VALUE

        for ((alias, canonical) in ALIASES) {
            val aliasClean = alias.replace(Regex("[^a-z]"), "")
            val d = editDistance(cleaned, aliasClean)
            if (d < bestDist && d <= threshold) {
                bestDist = d
                best = canonical
            }
        }
        return best
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
        }
        return dp[a.length][b.length]
    }
}
