package com.chamet.guesser

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Session 4 — race-strip reader.
 *
 * Finds the track between checkered start/finish lines, takes the **median
 * colour per column** (vehicles don't dominate), splits where colour changes,
 * merges neighbouring segments of the same type, and classifies each segment
 * against reference signatures from the 6 road images.
 *
 * Pure core works on [IntArray] RGB columns so unit tests need no Bitmap.
 * Runs once at race start (+ one re-check ~1 s later), never per frame.
 */
object RaceStripReader {

    /** Default track width on a 1080-px screenshot (plan: x 60→1050 = 990). */
    const val DEFAULT_TRACK_PX = 990.0

    /** Width tolerance for golden tests (~1% of track ≈ 10 px). */
    const val WIDTH_TOLERANCE_PX = 10.0

    data class Segment(
        val type: String,
        val px: Double,
        val fraction: Double,
        val confidence: Double
    )

    data class StripResult(
        val segments: List<Segment>,
        val trackWidthPx: Double,
        /** Overall confidence 0..1 (min segment confidence, penalised if visible road missing). */
        val confidence: Double,
        val visibleRoadPresent: Boolean,
        val startX: Int,
        val endX: Int
    ) {
        val roadTypes: List<String> get() = segments.map { it.type }
        val roadPx: List<Double> get() = segments.map { it.px }
        fun roadType(i: Int): String? = segments.getOrNull(i)?.type
        fun roadPx(i: Int): Double? = segments.getOrNull(i)?.px
    }

    /**
     * Reference RGB signatures (from plan + sampled race views).
     * Each road may have several sample colours (base + markings).
     */
    val ROAD_SIGNATURES: Map<String, List<IntArray>> = mapOf(
        "Desert" to listOf(
            intArrayOf(237, 210, 131),
            intArrayOf(220, 190, 110),
            intArrayOf(245, 225, 150)
        ),
        "Highway" to listOf(
            intArrayOf(140, 141, 145),
            intArrayOf(120, 121, 125),
            intArrayOf(200, 180, 40)   // yellow dash
        ),
        "Expressway" to listOf(
            intArrayOf(160, 160, 160),
            intArrayOf(175, 175, 175),
            intArrayOf(210, 210, 210)  // white line
        ),
        "Bumpy" to listOf(
            intArrayOf(97, 86, 58),
            intArrayOf(175, 150, 86),
            intArrayOf(110, 95, 65)
        ),
        "Dirt" to listOf(
            intArrayOf(150, 120, 80),
            intArrayOf(130, 100, 65),
            intArrayOf(170, 140, 95)
        ),
        "Potholes" to listOf(
            intArrayOf(90, 90, 95),
            intArrayOf(60, 60, 65),
            intArrayOf(110, 105, 100)
        )
    )

    // ---- public API ----

    /**
     * Analyse a full screenshot. Finds the strip region by checkered lines
     * (fractions of screen height/width so it works on other phone sizes).
     */
    fun analyse(
        bitmap: Bitmap,
        visibleRoad: String? = null,
        /** Fraction of screen height for strip top (plan ~75%). */
        stripTopFrac: Float = 0.75f,
        /** Fraction of screen height for strip bottom (plan ~93%). */
        stripBottomFrac: Float = 0.93f
    ): StripResult? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 100 || h < 100) return null

        val y0 = (h * stripTopFrac).toInt().coerceIn(0, h - 1)
        val y1 = (h * stripBottomFrac).toInt().coerceIn(y0 + 1, h)

        // Checkered start/finish: dark/light alternating near left and right edges
        val startX = findCheckeredEdge(bitmap, y0, y1, fromLeft = true)
        val endX = findCheckeredEdge(bitmap, y0, y1, fromLeft = false)
        if (endX - startX < 50) return null

        val columns = medianColumns(bitmap, startX, endX, y0, y1)
        return analyseColumns(columns, startX, endX, visibleRoad)
    }

    /**
     * Pure core: classify a sequence of median RGB columns (packed 0xRRGGBB).
     * Used by unit tests with synthetic golden layouts.
     */
    fun analyseColumns(
        columns: IntArray,
        startX: Int = 0,
        endX: Int = columns.size,
        visibleRoad: String? = null
    ): StripResult {
        val trackW = (endX - startX).toDouble().coerceAtLeast(1.0)
        if (columns.isEmpty()) {
            return StripResult(emptyList(), trackW, 0.0, false, startX, endX)
        }

        // Classify every column
        val labels = Array(columns.size) { i ->
            classifyRgb(red(columns[i]), green(columns[i]), blue(columns[i]))
        }

        // Split into runs, merge same-type neighbours, drop tiny noise (< 1% of track)
        val minSeg = max(3, (trackW * 0.01).toInt())
        val raw = mutableListOf<Triple<String, Int, Double>>() // type, widthPx, conf
        var i = 0
        while (i < labels.size) {
            val t = labels[i].first
            var j = i + 1
            var confSum = labels[i].second
            while (j < labels.size && labels[j].first == t) {
                confSum += labels[j].second
                j++
            }
            val width = j - i
            val conf = confSum / width
            if (raw.isNotEmpty() && raw.last().first == t) {
                val prev = raw.removeAt(raw.lastIndex)
                raw.add(Triple(t, prev.second + width, (prev.third * prev.second + conf * width) / (prev.second + width)))
            } else {
                raw.add(Triple(t, width, conf))
            }
            i = j
        }

        // Absorb tiny unknown/noise segments into neighbours
        val cleaned = mutableListOf<Triple<String, Int, Double>>()
        for (seg in raw) {
            if (seg.second < minSeg && cleaned.isNotEmpty() && seg.first == "???") {
                val prev = cleaned.removeAt(cleaned.lastIndex)
                cleaned.add(Triple(prev.first, prev.second + seg.second, prev.third))
            } else if (seg.second < minSeg && cleaned.isNotEmpty() &&
                seg.first != "???" && cleaned.last().first != "???"
            ) {
                // very thin stripe — merge into previous if same family otherwise keep
                val prev = cleaned.last()
                if (prev.first == seg.first) {
                    cleaned.removeAt(cleaned.lastIndex)
                    cleaned.add(Triple(prev.first, prev.second + seg.second,
                        (prev.third * prev.second + seg.third * seg.second) / (prev.second + seg.second)))
                } else {
                    cleaned.add(seg)
                }
            } else {
                cleaned.add(seg)
            }
        }

        // Final merge of consecutive same type
        val merged = mutableListOf<Triple<String, Int, Double>>()
        for (seg in cleaned) {
            if (merged.isNotEmpty() && merged.last().first == seg.first) {
                val prev = merged.removeAt(merged.lastIndex)
                merged.add(Triple(
                    seg.first,
                    prev.second + seg.second,
                    (prev.third * prev.second + seg.third * seg.second) / (prev.second + seg.second)
                ))
            } else {
                merged.add(seg)
            }
        }

        val segments = merged.map { (type, px, conf) ->
            Segment(type, px.toDouble(), px / trackW, conf)
        }

        val visibleOk = visibleRoad.isNullOrBlank() ||
            segments.any { it.type.equals(visibleRoad, true) }

        var overall = if (segments.isEmpty()) 0.0 else segments.minOf { it.confidence }
        if (!visibleOk) overall *= 0.5
        if (segments.any { it.type == "???" }) overall *= 0.7

        return StripResult(segments, trackW, overall.coerceIn(0.0, 1.0), visibleOk, startX, endX)
    }

    /**
     * Build a synthetic column array for tests: list of (packed RGB, widthPx).
     */
    fun syntheticColumns(parts: List<Pair<Int, Int>>): IntArray {
        val total = parts.sumOf { it.second }
        val out = IntArray(total)
        var i = 0
        for ((rgb, w) in parts) {
            repeat(w) { out[i++] = rgb }
        }
        return out
    }

    fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
    fun red(c: Int): Int = (c shr 16) and 0xFF
    fun green(c: Int): Int = (c shr 8) and 0xFF
    fun blue(c: Int): Int = c and 0xFF

    // ---- internals ----

    /** Median RGB of a vertical slice — packed 0xRRGGBB. */
    fun medianColumns(
        bitmap: Bitmap,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int
    ): IntArray {
        val width = (x1 - x0).coerceAtLeast(0)
        val height = (y1 - y0).coerceAtLeast(1)
        val out = IntArray(width)
        val rs = IntArray(height)
        val gs = IntArray(height)
        val bs = IntArray(height)
        for (x in 0 until width) {
            for (y in 0 until height) {
                val c = bitmap.getPixel(x0 + x, y0 + y)
                rs[y] = red(c)
                gs[y] = green(c)
                bs[y] = blue(c)
            }
            rs.sort(); gs.sort(); bs.sort()
            val mid = height / 2
            out[x] = rgb(rs[mid], gs[mid], bs[mid])
        }
        return out
    }

    /**
     * Scan from left or right for a checkered (high local contrast) vertical band.
     * Returns the x just inside the track.
     */
    fun findCheckeredEdge(
        bitmap: Bitmap,
        y0: Int,
        y1: Int,
        fromLeft: Boolean
    ): Int {
        val w = bitmap.width
        val band = max(8, w / 40)
        val step = max(1, (y1 - y0) / 12)
        fun contrastAt(x: Int): Double {
            var prev = -1
            var flips = 0
            var samples = 0
            var y = y0
            while (y < y1) {
                val c = bitmap.getPixel(x.coerceIn(0, w - 1), y)
                val lum = (red(c) + green(c) + blue(c)) / 3
                if (prev >= 0 && abs(lum - prev) > 40) flips++
                prev = lum
                samples++
                y += step
            }
            return if (samples == 0) 0.0 else flips.toDouble() / samples
        }
        if (fromLeft) {
            var bestX = 0
            var best = -1.0
            for (x in 0 until min(band * 3, w / 4)) {
                val c = contrastAt(x)
                if (c > best) { best = c; bestX = x }
            }
            return (bestX + band / 2).coerceIn(0, w / 3)
        } else {
            var bestX = w - 1
            var best = -1.0
            for (x in w - 1 downTo max(w * 3 / 4, w - band * 3)) {
                val c = contrastAt(x)
                if (c > best) { best = c; bestX = x }
            }
            return (bestX - band / 2).coerceIn(w * 2 / 3, w - 1)
        }
    }

    /**
     * Classify one RGB sample → (road name or "???", confidence 0..1).
     */
    fun classifyRgb(r: Int, g: Int, b: Int): Pair<String, Double> {
        var bestName = "???"
        var bestDist = Double.MAX_VALUE
        for ((name, samples) in ROAD_SIGNATURES) {
            for (s in samples) {
                val d = colourDistance(r, g, b, s[0], s[1], s[2])
                if (d < bestDist) {
                    bestDist = d
                    bestName = name
                }
            }
        }
        // Distance thresholds: ≤25 excellent, ≤45 ok, else unknown
        val conf = when {
            bestDist <= 20 -> 1.0
            bestDist <= 35 -> 0.85
            bestDist <= 50 -> 0.65
            bestDist <= 70 -> 0.4
            else -> {
                bestName = "???"
                0.15
            }
        }
        return bestName to conf
    }

    private fun colourDistance(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Double {
        val dr = (r1 - r2).toDouble()
        val dg = (g1 - g2).toDouble()
        val db = (b1 - b2).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }
}
