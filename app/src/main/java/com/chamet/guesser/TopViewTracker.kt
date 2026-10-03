package com.chamet.guesser

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Session 5 — top-view vehicle tracker.
 *
 * Lane order = card order (left card = top lane, mid = mid, right = bottom),
 * confirmed on multiple races. Tracks front-edge x at ~4 fps; progress =
 * (x − start) ÷ (finish − start). Stores samples in [race_track].
 *
 * Pure core works on numeric samples so unit tests need no Bitmap.
 * Live capture uses a downscaled strip crop and stops at the finish.
 */
object TopViewTracker {

    const val MAX_SAMPLES = 100
    /** px/s ÷ table-speed should agree within this relative error. */
    const val DRIFT_THRESHOLD = 0.05
    /** Progress ≥ this counts as crossed the finish. */
    const val FINISH_PROGRESS = 0.98

    data class Sample(
        /** ms from race start */
        val timeMs: Long,
        /** front-edge x in track coordinates (0 = start, trackWidth = finish) */
        val x1: Float,
        val x2: Float,
        val x3: Float
    ) {
        fun x(lane: Int): Float = when (lane) {
            1 -> x1; 2 -> x2; else -> x3
        }
    }

    data class TrackState(
        val laneCars: List<String>,          // always 3: top/mid/bottom = cards L/M/R
        val samples: List<Sample>,
        val startX: Float,
        val finishX: Float,
        val trackWidth: Float
    ) {
        fun progress(sample: Sample, lane: Int): Float {
            val span = (finishX - startX).coerceAtLeast(1f)
            return ((sample.x(lane) - startX) / span).coerceIn(0f, 1.2f)
        }
    }

    data class FinishResult(
        val order: List<String>,             // crossing order, first = winner
        val winner: String?,
        val finishTimesMs: Map<String, Long>,
        val allFinished: Boolean
    )

    data class DriftResult(
        /** true when any vehicle's implied scale differs > DRIFT_THRESHOLD from the median */
        val driftDetected: Boolean,
        /** scale = pxPerSec / tableSpeed for each car that could be measured */
        val scales: Map<String, Double>,
        val medianScale: Double?,
        val flagMessage: String?
    )

    // ---- lane assignment ----

    /**
     * Lane k = card k. Cross-check optional colour tags from the top-view strip
     * (sprite template matching lands here once sprites are available).
     *
     * @param cardCars left/mid/right card names (positionCars)
     * @param colourHints optional detected top-view colour → car guesses per lane
     */
    fun assignLanes(
        cardCars: List<String>,
        colourHints: List<String?> = emptyList()
    ): List<String> {
        val lanes = MutableList(3) { i ->
            cardCars.getOrNull(i)?.takeIf { it.isNotBlank() && it != "—" } ?: "—"
        }
        // Soft cross-check: if a colour hint strongly disagrees, keep the card
        // name but callers can log the mismatch. Card order is authoritative.
        if (colourHints.size == 3) {
            for (i in 0..2) {
                val hint = colourHints[i] ?: continue
                if (lanes[i] == "—" && hint.isNotBlank()) lanes[i] = hint
            }
        }
        return lanes
    }

    /** Encode lane cars for DB: "Car|ATV|SUV". */
    fun encodeLaneCars(lanes: List<String>): String =
        (0..2).joinToString("|") { lanes.getOrElse(it) { "—" } }

    fun decodeLaneCars(s: String?): List<String> {
        if (s.isNullOrBlank()) return listOf("—", "—", "—")
        val parts = s.split("|")
        return listOf(
            parts.getOrElse(0) { "—" },
            parts.getOrElse(1) { "—" },
            parts.getOrElse(2) { "—" }
        )
    }

    // ---- sampling ----

    /**
     * Append a sample, keeping at most [MAX_SAMPLES] (evenly thinned if over).
     */
    fun appendSample(existing: List<Sample>, next: Sample): List<Sample> {
        val combined = existing + next
        if (combined.size <= MAX_SAMPLES) return combined
        // Keep first, last, and evenly spaced middle points
        val out = ArrayList<Sample>(MAX_SAMPLES)
        val last = combined.size - 1
        for (i in 0 until MAX_SAMPLES) {
            val idx = (i * last) / (MAX_SAMPLES - 1)
            out.add(combined[idx])
        }
        return out.distinctBy { it.timeMs }
    }

    fun toEntities(roundUuid: String, samples: List<Sample>): List<RaceTrackEntity> =
        samples.map {
            RaceTrackEntity(roundUuid, it.timeMs, it.x1, it.x2, it.x3)
        }

    fun fromEntities(rows: List<RaceTrackEntity>): List<Sample> =
        rows.map { Sample(it.timeMs, it.x1, it.x2, it.x3) }

    // ---- progress & finish ----

    /**
     * Detect crossing order from samples. A lane finishes when progress first
     * reaches [FINISH_PROGRESS]. Order is by finish timeMs (then by lane).
     */
    fun detectFinish(
        state: TrackState,
        samples: List<Sample> = state.samples
    ): FinishResult {
        val finishMs = mutableMapOf<Int, Long>() // lane 1..3 → time
        for (s in samples.sortedBy { it.timeMs }) {
            for (lane in 1..3) {
                if (lane in finishMs) continue
                if (state.progress(s, lane) >= FINISH_PROGRESS) {
                    finishMs[lane] = s.timeMs
                }
            }
        }
        val orderedLanes = finishMs.entries.sortedWith(
            compareBy<Map.Entry<Int, Long>> { it.value }.thenBy { it.key }
        ).map { it.key }
        val order = orderedLanes.map { lane ->
            state.laneCars.getOrElse(lane - 1) { "—" }
        }.filter { it != "—" }
        val times = orderedLanes.associate { lane ->
            state.laneCars.getOrElse(lane - 1) { "—" } to finishMs.getValue(lane)
        }.filterKeys { it != "—" }
        return FinishResult(
            order = order,
            winner = order.firstOrNull(),
            finishTimesMs = times,
            allFinished = finishMs.size >= state.laneCars.count { it != "—" }
        )
    }

    fun encodeFinishOrder(order: List<String>): String = order.joinToString("|")

    // ---- speed drift ----

    /**
     * For each vehicle, estimate px/s over the longest stretch with a known
     * road type, then scale = (px/s) / tableSpeed. If any scale differs from
     * the median by more than [DRIFT_THRESHOLD], raise the drift flag.
     *
     * @param roadSegments ordered (type, lengthPx) covering the track left→right
     */
    fun checkSpeedDrift(
        state: TrackState,
        samples: List<Sample>,
        roadSegments: List<Pair<String, Double>>,
        laneCars: List<String> = state.laneCars
    ): DriftResult {
        if (samples.size < 2 || roadSegments.isEmpty()) {
            return DriftResult(false, emptyMap(), null, null)
        }
        val scales = mutableMapOf<String, Double>()
        for (lane in 1..3) {
            val car = laneCars.getOrElse(lane - 1) { "—" }
            if (car == "—" || car !in SpeedDatabase.CAR_SPEEDS) continue
            val pts = samples.map { it.timeMs.toDouble() to state.progress(it, lane).toDouble() }
            val dt = pts.last().first - pts.first().first
            val dp = pts.last().second - pts.first().second
            if (dt < 50 || dp < 0.05) continue
            val pxPerSec = (dp * state.trackWidth) / (dt / 1000.0)
            // Approximate table speed: distance-weighted average over segments
            val totalPx = roadSegments.sumOf { it.second }.coerceAtLeast(1.0)
            var weightedSpeed = 0.0
            for ((type, px) in roadSegments) {
                val sp = SpeedDatabase.speedOf(car, type).toDouble()
                if (sp <= 0) continue
                weightedSpeed += sp * (px / totalPx)
            }
            if (weightedSpeed <= 0) continue
            scales[car] = pxPerSec / weightedSpeed
        }
        if (scales.size < 2) {
            return DriftResult(false, scales, scales.values.firstOrNull(), null)
        }
        val sorted = scales.values.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        var drift = false
        val offenders = mutableListOf<String>()
        for ((car, scale) in scales) {
            if (median > 0 && abs(scale - median) / median > DRIFT_THRESHOLD) {
                drift = true
                offenders += car
            }
        }
        val msg = if (drift) {
            "speed table drift: ${offenders.joinToString()} off >${(DRIFT_THRESHOLD * 100).toInt()}% from median scale ${"%.3f".format(median)}"
        } else null
        return DriftResult(drift, scales, median, msg)
    }

    /**
     * Progress of each lane at a given sample (for UI / validation).
     */
    fun progressSnapshot(state: TrackState, sample: Sample): List<Float> =
        listOf(
            state.progress(sample, 1),
            state.progress(sample, 2),
            state.progress(sample, 3)
        )

    /**
     * Build track geometry from strip start/finish x (track coordinates).
     */
    fun geometry(startX: Float, finishX: Float): Triple<Float, Float, Float> {
        val lo = min(startX, finishX)
        val hi = max(startX, finishX)
        return Triple(lo, hi, (hi - lo).coerceAtLeast(1f))
    }

    // Known top-view sprite colour tags (approximate). Used as optional hints.
    val SPRITE_COLOUR_HINTS: Map<String, IntArray> = mapOf(
        "Motorcycle" to intArrayOf(40, 160, 60),      // green
        "Supercar" to intArrayOf(180, 180, 190),      // silver
        "Car" to intArrayOf(220, 100, 140),           // pink
        "Sports Car" to intArrayOf(40, 90, 200),      // blue
        "SUV" to intArrayOf(120, 60, 160),            // purple
        "Monster Truck" to intArrayOf(50, 140, 50),   // green
        "ORV" to intArrayOf(160, 110, 50),            // brown / tan
        "ATV" to intArrayOf(200, 40, 40),             // red
        "Stock Car" to intArrayOf(240, 200, 40)       // yellow
    )

    /**
     * Sample three lane progress values from a top-view strip region of a screenshot.
     * Scans each lane band for the rightmost pixel close to a known sprite colour.
     * Returns x positions in image coordinates (caller maps to track space).
     */
    fun sampleLaneProgress(
        pixels: IntArray,
        width: Int,
        height: Int,
        laneYs: IntArray = intArrayOf(
            (height * 0.25f).toInt(),
            (height * 0.50f).toInt(),
            (height * 0.75f).toInt()
        )
    ): FloatArray {
        val xs = FloatArray(3) { 0f }
        for (lane in 0..2) {
            val y = laneYs[lane].coerceIn(0, height - 1)
            var bestX = 0
            for (x in 0 until width) {
                val c = pixels[y * width + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // skip near-black / near-white track
                if (r + g + b < 60 || r + g + b > 720) continue
                if (nearestSpriteHint(r, g, b, maxDist = 70.0) != null) {
                    bestX = x
                }
            }
            xs[lane] = bestX.toFloat()
        }
        return xs
    }

    fun nearestSpriteHint(r: Int, g: Int, b: Int, maxDist: Double = 55.0): String? {
        var best: String? = null
        var bestD = maxDist
        for ((name, rgb) in SPRITE_COLOUR_HINTS) {
            val dr = (r - rgb[0]).toDouble()
            val dg = (g - rgb[1]).toDouble()
            val db = (b - rgb[2]).toDouble()
            val d = kotlin.math.sqrt(dr * dr + dg * dg + db * db)
            if (d < bestD) {
                bestD = d
                best = name
            }
        }
        return best
    }
}
