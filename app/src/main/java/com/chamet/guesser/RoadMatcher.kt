package com.chamet.guesser

/**
 * Fuzzy-match OCR / AI banner text to the 6 known road names.
 * Banner text only — never texture or color (Session 2).
 */
object RoadMatcher {

    val KNOWN_ROADS: List<String> = SpeedDatabase.ROADS

    /** OCR aliases → canonical SpeedDatabase name. */
    private val ALIASES: Map<String, String> = mapOf(
        "highway" to "Highway",
        "high way" to "Highway",
        "high-way" to "Highway",
        "hwy" to "Highway",
        "expressway" to "Expressway",
        "express way" to "Expressway",
        "express-way" to "Expressway",
        "express" to "Expressway",
        "dirt" to "Dirt",
        "dirt road" to "Dirt",
        "bumpy" to "Bumpy",
        "bump" to "Bumpy",
        "bumpy road" to "Bumpy",
        "potholes" to "Potholes",
        "pothole" to "Potholes",
        "pot holes" to "Potholes",
        "pot-holes" to "Potholes",
        "pots" to "Potholes",
        "desert" to "Desert",
        "sand" to "Desert"
    )

    /**
     * Best road match for a single OCR line, or null if nothing is close enough.
     */
    fun match(raw: String, editThreshold: Int = 2): String? {
        val cleaned = normalize(raw)
        if (cleaned.isEmpty()) return null

        ALIASES[cleaned]?.let { return it }

        val sorted = ALIASES.keys.sortedByDescending { it.length }
        for (alias in sorted) {
            if (cleaned.contains(alias)) return ALIASES[alias]
        }

        var best: String? = null
        var bestDist = Int.MAX_VALUE
        val candidates = (ALIASES.keys + KNOWN_ROADS.map { it.lowercase() }).distinct()
        for (cand in candidates) {
            val d = editDistance(cleaned.replace(" ", ""), cand.replace(" ", ""))
            if (d < bestDist && d <= editThreshold) {
                bestDist = d
                best = ALIASES[cand] ?: KNOWN_ROADS.find { it.equals(cand, true) }
            }
        }
        return best
    }

    /** Scan many OCR lines; return the first confident road match. */
    fun matchLines(lines: List<String>): String? {
        for (line in lines) {
            match(line)?.let { return it }
        }
        return null
    }

    fun isKnown(name: String?): Boolean =
        !name.isNullOrBlank() && KNOWN_ROADS.any { it.equals(name.trim(), true) }

    fun canonical(name: String?): String? {
        if (name.isNullOrBlank()) return null
        return KNOWN_ROADS.find { it.equals(name.trim(), true) } ?: match(name)
    }

    private fun normalize(raw: String): String =
        raw.lowercase()
            .replace("\n", " ")
            .replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

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
