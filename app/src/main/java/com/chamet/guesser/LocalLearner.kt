package com.chamet.guesser

import kotlin.math.abs

/**
 * Session 8 — local learning from logged rounds (counts only, no server).
 *
 * Re-estimates:
 *  - length prior from rounds with known roadPx + roadType
 *  - road-family weights from (visibleRoad → other segment types)
 *
 * A candidate is activated only if it does not do worse than the current
 * params on a held-out slice (model accuracy). Minimum sample size required.
 * [rollback] restores the previous snapshot.
 */
object LocalLearner {

    const val MIN_ROUNDS = 30
    const val HOLDOUT_FRACTION = 0.25

    data class LearnResult(
        val applied: Boolean,
        val message: String,
        val candidateVersion: String?,
        val trainHits: Int = 0,
        val trainKnown: Int = 0,
        val holdoutOldPct: Double = 0.0,
        val holdoutNewPct: Double = 0.0
    )

    /** Previous params for one-tap rollback (in-memory + optional Prefs). */
    @Volatile
    var previous: EngineParams? = null
        private set

    /**
     * Build a candidate from rounds that have a full strip layout, evaluate on
     * holdout, and activate only if holdout model accuracy ≥ current.
     */
    fun tryLearn(rows: List<RoundEntity>): LearnResult {
        val withLayout = rows.filter { hasLayout(it) }
        if (withLayout.size < MIN_ROUNDS) {
            return LearnResult(
                false,
                "Need $MIN_ROUNDS rounds with full layout (have ${withLayout.size})",
                null
            )
        }

        val sorted = withLayout.sortedBy { it.timestamp }
        val holdoutN = maxOf(1, (sorted.size * HOLDOUT_FRACTION).toInt())
        val holdout = sorted.takeLast(holdoutN)
        val train = sorted.dropLast(holdoutN)
        if (train.isEmpty()) {
            return LearnResult(false, "Not enough train rounds after holdout", null)
        }

        val candidate = buildCandidate(train) ?: return LearnResult(
            false, "Could not build candidate from train set", null
        )

        val oldParams = EngineParams.active
        val oldHoldout = accuracyOn(holdout, oldParams)
        val newHoldout = accuracyOn(holdout, candidate)
        val (oldHits, oldKnown, oldPct) = oldHoldout
        val (newHits, newKnown, newPct) = newHoldout

        if (newKnown == 0) {
            return LearnResult(false, "Holdout has no model+winner pairs", candidate.version)
        }
        if (newKnown < 5) {
            return LearnResult(false, "Holdout too small to accept (n=$newKnown)", null, 0, newKnown, oldPct, newPct)
        }
        // Require strict improvement when metrics differ; block no-op equality when candidate has no measured lengths
        val candHasLengths = (candidate.lengthPriorPx.size) > EngineParams.active.lengthPriorPx.size
        if (newPct + 1e-9 < oldPct || (!candHasLengths && abs(newPct - oldPct) < 1e-9 && newKnown == oldKnown)) {
            return LearnResult(
                false,
                "Candidate worse or no-op on holdout (${"%.1f".format(newPct)}% < ${"%.1f".format(oldPct)}%) — not applied",
                candidate.version,
                newHits, newKnown, oldPct, newPct
            )
        }

        previous = oldParams
        EngineParams.use(candidate)
        return LearnResult(
            true,
            "Applied ${candidate.version}: holdout ${"%.1f".format(newPct)}% ≥ ${"%.1f".format(oldPct)}% (n=$newKnown)",
            candidate.version,
            newHits, newKnown, oldPct, newPct
        )
    }

    fun rollback(): Boolean {
        val prev = previous ?: return false
        EngineParams.use(prev)
        previous = null
        return true
    }

    fun clearPrevious() {
        previous = null
    }

    fun setPrevious(params: EngineParams) {
        previous = params
    }

    fun hasLayout(r: RoundEntity): Boolean {
        val types = listOfNotNull(r.roadType1, r.roadType2, r.roadType3)
            .filter { it.isNotBlank() && it != "???" }
        val pxs = listOfNotNull(r.roadPx1, r.roadPx2, r.roadPx3).filter { it > 0 }
        return types.size >= 2 && pxs.size >= 2
    }

    fun segmentsOf(r: RoundEntity): List<Pair<String, Double>> {
        val out = mutableListOf<Pair<String, Double>>()
        fun add(t: String?, px: Double?) {
            if (!t.isNullOrBlank() && t != "???" && px != null && px > 0) out += t to px
        }
        add(r.roadType1, r.roadPx1)
        add(r.roadType2, r.roadPx2)
        add(r.roadType3, r.roadPx3)
        return out
    }

    fun buildCandidate(train: List<RoundEntity>): EngineParams? {
        val lengthPrior = mutableListOf<Pair<String, Double>>()
        val familyCounts = mutableMapOf<String, MutableMap<String, Int>>() // visible → type → count

        for (r in train) {
            val segs = segmentsOf(r)
            for ((t, px) in segs) lengthPrior += t to px
            val visible = r.visibleRoad?.takeIf { RoadMatcher.isKnown(it) }
                ?: segs.firstOrNull()?.first
                ?: continue
            val bag = familyCounts.getOrPut(visible) { mutableMapOf() }
            for ((t, _) in segs) {
                bag[t] = (bag[t] ?: 0) + 1
            }
        }
        val measured = DataIntegrity.measuredLengthPrior(train, minSegments = 8)
        if (measured != null) {
            lengthPrior.clear()
            lengthPrior.addAll(measured)
        }
        if (lengthPrior.size < 8) return null

        val families = EngineParams.DEFAULT_FAMILIES.toMutableMap()
        for ((visible, counts) in familyCounts) {
            // Expand counts into a list (repetitions = weight), keep at least prior family
            val expanded = mutableListOf<String>()
            counts.entries.sortedByDescending { it.value }.forEach { (t, c) ->
                repeat(c.coerceAtLeast(1)) { expanded += t }
            }
            if (expanded.isNotEmpty()) families[visible] = expanded
        }

        val stamp = train.size
        return EngineParams.DEFAULT.copy(
            version = "8.1.learn.$stamp",
            lengthPriorPx = lengthPrior,
            roadFamilies = families
        )
    }

    /** Model accuracy on rows using exact finish times with the given params. */
    fun accuracyOn(
        rows: List<RoundEntity>,
        params: EngineParams
    ): Triple<Int, Int, Double> {
        var hits = 0
        var known = 0
        val prev = EngineParams.active
        try {
            EngineParams.use(params)
            for (r in rows) {
                val segs = segmentsOf(r)
                if (segs.size < 2) continue
                val cars = listOfNotNull(r.v1, r.v2, r.v3).filter { it.isNotBlank() }
                if (cars.isEmpty()) continue
                val real = r.winner ?: continue
                val model = FinishTimeModel.compute(cars, segs)?.modelWinner ?: continue
                known++
                if (FinishTimeModel.modelHit(model, real)) hits++
            }
        } finally {
            EngineParams.use(prev)
        }
        val pct = if (known == 0) 0.0 else hits * 100.0 / known
        return Triple(hits, known, pct)
    }
}
