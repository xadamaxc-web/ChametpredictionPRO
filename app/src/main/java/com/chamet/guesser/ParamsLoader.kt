package com.chamet.guesser

import android.content.Context
import java.io.File

/**
 * Load [EngineParams] from JSON (assets/params.json or a learned file).
 * Pure parse path has no Android dependency so unit tests can feed a string.
 */
object ParamsLoader {

    const val ASSET_NAME = "params.json"
    const val LEARNED_FILE = "params_learned.json"
    const val ROLLBACK_FILE = "params_rollback.json"
    const val SCHEMA_VERSION = 1

    /**
     * Try learned file first, then assets. On any failure keep [EngineParams.DEFAULT].
     */
    fun loadIntoEngine(context: Context): EngineParams {
        val learned = File(context.filesDir, LEARNED_FILE)
        val json = when {
            learned.exists() -> learned.readText()
            else -> try {
                context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                null
            }
        } ?: return EngineParams.DEFAULT

        val parsed = parse(json) ?: return EngineParams.DEFAULT
        EngineParams.use(parsed)
        return parsed
    }

    fun saveLearned(context: Context, params: EngineParams) {
        try {
            File(context.filesDir, LEARNED_FILE).writeText(toJson(params))
        } catch (_: Exception) { /* best-effort */ }
    }

    fun clearLearned(context: Context) {
        try {
            File(context.filesDir, LEARNED_FILE).delete()
        } catch (_: Exception) { }
    }

    fun saveRollback(context: Context, params: EngineParams) {
        try {
            File(context.filesDir, ROLLBACK_FILE).writeText(toJson(params))
        } catch (_: Exception) { }
    }

    fun loadRollback(context: Context): EngineParams? {
        val f = File(context.filesDir, ROLLBACK_FILE)
        if (!f.exists()) return null
        return try { parse(f.readText()) } catch (_: Exception) { null }
    }

    fun clearRollback(context: Context) {
        try { File(context.filesDir, ROLLBACK_FILE).delete() } catch (_: Exception) { }
    }

    /** Log when falling back to compiled defaults. */
    fun loadIntoEngineLogged(context: Context): EngineParams {
        val learned = File(context.filesDir, LEARNED_FILE)
        val fromLearned = learned.exists()
        val json = when {
            fromLearned -> learned.readText()
            else -> try {
                context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                android.util.Log.w("ParamsLoader", "assets/$ASSET_NAME missing: ${e.message}")
                null
            }
        }
        if (json == null) {
            android.util.Log.w("ParamsLoader", "Using EngineParams.DEFAULT (no JSON)")
            EngineParams.resetToDefault()
            return EngineParams.DEFAULT
        }
        val parsed = parse(json)
        if (parsed == null) {
            android.util.Log.w("ParamsLoader", "JSON parse failed — using DEFAULT")
            EngineParams.resetToDefault()
            return EngineParams.DEFAULT
        }
        EngineParams.use(parsed)
        android.util.Log.i(
            "ParamsLoader",
            "Loaded params ${parsed.version} from ${if (fromLearned) "learned" else "assets"}"
        )
        return parsed
    }

    /** Pure JSON → EngineParams. Returns null if schema is unusable. */
    fun parse(json: String): EngineParams? {
        if (json.isBlank()) return null
        // schema_version optional; reject only if present and incompatible
        val schema = intField(json, "schema_version")
        if (schema != null && schema > SCHEMA_VERSION) return null

        val version = stringField(json, "version") ?: "8.1.0"
        val simulations = intField(json, "simulations") ?: 2000
        val defaultSeed = longField(json, "defaultSeed") ?: 42L
        val trackWidth = doubleField(json, "trackWidthPx") ?: 990.0
        val segMin = intField(json, "segmentCountMin") ?: 2
        val segMax = intField(json, "segmentCountMax") ?: 3

        val roads = stringArray(json, "roads").ifEmpty { EngineParams.DEFAULT.roads }
        val speeds = parseSpeeds(json).ifEmpty { EngineParams.DEFAULT_SPEEDS }
        val families = parseFamilies(json).ifEmpty { EngineParams.DEFAULT_FAMILIES }
        val lengths = parseLengths(json).ifEmpty { EngineParams.DEFAULT_LENGTH_PRIOR }
        val bias = parsePositionBias(json)

        return EngineParams(
            version = version,
            simulations = simulations,
            defaultSeed = defaultSeed,
            roads = roads,
            speeds = speeds,
            roadFamilies = families,
            lengthPriorPx = lengths,
            trackWidthPx = trackWidth,
            segmentCountMin = segMin,
            segmentCountMax = segMax,
            positionBias = bias
        )
    }

    fun toJson(p: EngineParams): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("""  "schema_version": $SCHEMA_VERSION,""").append('\n')
        sb.append("""  "version": "${p.version}",""").append('\n')
        sb.append("""  "simulations": ${p.simulations},""").append('\n')
        sb.append("""  "defaultSeed": ${p.defaultSeed},""").append('\n')
        sb.append("""  "roads": [${p.roads.joinToString(",") { "\"$it\"" }}],""").append('\n')
        sb.append("  \"speeds\": {\n")
        p.speeds.entries.forEachIndexed { i, (car, roads) ->
            val body = roads.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" }
            sb.append("    \"$car\": {$body}")
            if (i < p.speeds.size - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append("  },\n")
        sb.append("  \"roadFamilies\": {\n")
        p.roadFamilies.entries.forEachIndexed { i, (k, list) ->
            sb.append("    \"$k\": [${list.joinToString(",") { "\"$it\"" }}]")
            if (i < p.roadFamilies.size - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append("  },\n")
        sb.append("  \"lengthPriorPx\": [\n")
        p.lengthPriorPx.forEachIndexed { i, (t, px) ->
            sb.append("    {\"type\": \"$t\", \"px\": $px}")
            if (i < p.lengthPriorPx.size - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ],\n")
        sb.append("""  "trackWidthPx": ${p.trackWidthPx},""").append('\n')
        sb.append("""  "segmentCountMin": ${p.segmentCountMin},""").append('\n')
        sb.append("""  "segmentCountMax": ${p.segmentCountMax},""").append('\n')
        sb.append("""  "position_bias": {"p1": ${p.positionBias.first}, "p2": ${p.positionBias.second}, "p3": ${p.positionBias.third}}""").append('\n')
        sb.append("}\n")
        return sb.toString()
    }

    // ---- minimal field extractors (no org.json dependency) ----

    private fun stringField(json: String, key: String): String? {
        val re = Regex(""""$key"\s*:\s*"([^"]*)"""")
        return re.find(json)?.groupValues?.get(1)
    }

    private fun intField(json: String, key: String): Int? {
        val re = Regex(""""$key"\s*:\s*(-?\d+)""")
        return re.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun longField(json: String, key: String): Long? {
        val re = Regex(""""$key"\s*:\s*(-?\d+)""")
        return re.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun doubleField(json: String, key: String): Double? {
        val re = Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?)""")
        return re.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun stringArray(json: String, key: String): List<String> {
        val re = Regex(""""$key"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val body = re.find(json)?.groupValues?.get(1) ?: return emptyList()
        return Regex(""""([^"]+)"""").findAll(body).map { it.groupValues[1] }.toList()
    }

    private fun parseSpeeds(json: String): Map<String, Map<String, Int>> {
        val block = objectBlock(json, "speeds") ?: return emptyMap()
        val out = mutableMapOf<String, Map<String, Int>>()
        val carRe = Regex(""""([^"]+)"\s*:\s*\{([^}]*)\}""")
        for (m in carRe.findAll(block)) {
            val car = m.groupValues[1]
            val inner = m.groupValues[2]
            val roads = mutableMapOf<String, Int>()
            val roadRe = Regex(""""([^"]+)"\s*:\s*(\d+)""")
            for (r in roadRe.findAll(inner)) {
                roads[r.groupValues[1]] = r.groupValues[2].toInt()
            }
            if (roads.isNotEmpty()) out[car] = roads
        }
        return out
    }

    private fun parseFamilies(json: String): Map<String, List<String>> {
        val block = objectBlock(json, "roadFamilies") ?: return emptyMap()
        val out = mutableMapOf<String, List<String>>()
        val famRe = Regex(""""([^"]+)"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        for (m in famRe.findAll(block)) {
            val key = m.groupValues[1]
            val list = Regex(""""([^"]+)"""").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
            if (list.isNotEmpty()) out[key] = list
        }
        return out
    }

    private fun parseLengths(json: String): List<Pair<String, Double>> {
        val re = Regex(""""type"\s*:\s*"([^"]+)"\s*,\s*"px"\s*:\s*(\d+(?:\.\d+)?)""")
        return re.findAll(json).map {
            it.groupValues[1] to it.groupValues[2].toDouble()
        }.toList()
    }

    private fun parsePositionBias(json: String): Triple<Double, Double, Double> {
        val p1 = Regex("\"p1\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").find(json)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
        val p2 = Regex("\"p2\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").find(json)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
        val p3 = Regex("\"p3\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").find(json)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
        return Triple(p1, p2, p3)
    }

    private fun objectBlock(json: String, key: String): String? {
        val startRe = Regex(""""$key"\s*:\s*\{""")
        val m = startRe.find(json) ?: return null
        var i = m.range.last
        var depth = 1
        val sb = StringBuilder()
        while (i + 1 < json.length && depth > 0) {
            i++
            val c = json[i]
            when (c) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth > 0) sb.append(c)
        }
        return sb.toString()
    }
}
