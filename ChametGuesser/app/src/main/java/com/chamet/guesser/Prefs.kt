package com.chamet.guesser

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

object Prefs {
    private const val NAME = "chamet_guesser_prefs"

    private fun p(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // Balance / session keys (also used by WonLostLogger)
    const val KEY_BALANCE = "diamondBalance"
    const val KEY_SESSION = "sessionId"
    const val KEY_LAST_TS = "lastRoundTimestamp"

    // A locked round whose result (WON / LOST / NO BET) was not tapped yet blocks a new capture
    fun roundUnrecorded(ctx: Context): Boolean = p(ctx).getBoolean("roundUnrecorded", false)
    fun setRoundUnrecorded(ctx: Context, v: Boolean) =
        p(ctx).edit().putBoolean("roundUnrecorded", v).apply()

    // OCR mode: mlkit | hybrid | ai
    fun ocrMode(ctx: Context): String = p(ctx).getString("ocrMode", "hybrid") ?: "hybrid"
    fun setOcrMode(ctx: Context, mode: String) =
        p(ctx).edit().putString("ocrMode", mode).apply()

    // Provider slots 1-3: gemini | groq | openrouter
    data class ProviderSlot(
        val id: String,
        val enabled: Boolean,
        val apiKey: String,
        val model: String
    )

    fun slot(ctx: Context, index: Int): ProviderSlot {
        val id = when (index) {
            1 -> p(ctx).getString("slot1_id", "gemini") ?: "gemini"
            2 -> p(ctx).getString("slot2_id", "groq") ?: "groq"
            else -> p(ctx).getString("slot3_id", "openrouter") ?: "openrouter"
        }
        return ProviderSlot(
            id = id,
            enabled = p(ctx).getBoolean("slot${index}_enabled", index == 1),
            apiKey = p(ctx).getString("slot${index}_key", "") ?: "",
            model = p(ctx).getString("slot${index}_model", defaultModel(id)) ?: defaultModel(id)
        )
    }

    fun setSlot(
        ctx: Context,
        index: Int,
        enabled: Boolean,
        apiKey: String,
        model: String,
        id: String
    ) {
        p(ctx).edit()
            .putBoolean("slot${index}_enabled", enabled)
            .putString("slot${index}_key", apiKey)
            .putString("slot${index}_model", model)
            .putString("slot${index}_id", id)
            .apply()
    }

    fun defaultModel(id: String): String = when (id) {
        "gemini" -> "gemini-2.0-flash"
        "groq" -> "meta-llama/llama-4-scout-17b-16e-instruct"
        "openrouter" -> "qwen/qwen-2.5-vl-7b-instruct:free"
        else -> "gemini-2.0-flash"
    }

    /** Mark provider rate-limited until tomorrow */
    fun markExhausted(ctx: Context, providerId: String) {
        val cal = Calendar.getInstance()
        val dayKey = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
        p(ctx).edit().putString("exhausted_${providerId}", dayKey).apply()
    }

    fun isExhausted(ctx: Context, providerId: String): Boolean {
        val cal = Calendar.getInstance()
        val dayKey = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
        return p(ctx).getString("exhausted_${providerId}", "") == dayKey
    }

    fun bumpCallCount(ctx: Context, providerId: String) {
        val k = "calls_$providerId"
        p(ctx).edit().putInt(k, p(ctx).getInt(k, 0) + 1).apply()
    }

    // Auto-hide overlay outside Chamet
    fun autoHideChametOnly(ctx: Context): Boolean =
        p(ctx).getBoolean("autoHideChametOnly", true)

    fun setAutoHideChametOnly(ctx: Context, v: Boolean) =
        p(ctx).edit().putBoolean("autoHideChametOnly", v).apply()

    // Position bias
    fun positionBiasEnabled(ctx: Context): Boolean =
        p(ctx).getBoolean("positionBiasEnabled", true)

    fun setPositionBiasEnabled(ctx: Context, v: Boolean) =
        p(ctx).edit().putBoolean("positionBiasEnabled", v).apply()

    fun getPositionMultipliers(ctx: Context): Triple<Double, Double, Double> {
        val a = p(ctx).getFloat("bias_p1", 1.0f).toDouble()
        val b = p(ctx).getFloat("bias_p2", 1.0f).toDouble()
        val c = p(ctx).getFloat("bias_p3", 1.0f).toDouble()
        return Triple(a, b, c)
    }

    fun setPositionMultipliers(ctx: Context, p1: Double, p2: Double, p3: Double) {
        p(ctx).edit()
            .putFloat("bias_p1", p1.toFloat())
            .putFloat("bias_p2", p2.toFloat())
            .putFloat("bias_p3", p3.toFloat())
            .apply()
    }

    fun biasNote(ctx: Context): String {
        if (!positionBiasEnabled(ctx)) return ""
        val (a, b, c) = getPositionMultipliers(ctx)
        if (a == 1.0 && b == 1.0 && c == 1.0) return ""
        return "P1=" + String.format("%.2f", a) +
            " P2=" + String.format("%.2f", b) +
            " P3=" + String.format("%.2f", c)
    }

    // ---- Session 2: manual road rate (target < ~5%) ----
    fun roadReadTotal(ctx: Context): Int = p(ctx).getInt("road_read_total", 0)
    fun roadManualCount(ctx: Context): Int = p(ctx).getInt("road_manual_count", 0)

    /** Record one round's road source: screen / ai / manual / unknown. */
    fun recordRoadSource(ctx: Context, source: String?) {
        val ed = p(ctx).edit()
        ed.putInt("road_read_total", roadReadTotal(ctx) + 1)
        if (source == "manual") {
            ed.putInt("road_manual_count", roadManualCount(ctx) + 1)
        }
        ed.apply()
    }

    /** Manual rate as percent of all recorded road reads, or null if no data. */
    fun manualRoadRatePct(ctx: Context): Double? {
        val total = roadReadTotal(ctx)
        if (total == 0) return null
        return roadManualCount(ctx) * 100.0 / total
    }
}

    // ---- Session 9: controller ----
    /** auto = capture freely; manual = user taps floating button only */
    fun captureMode(ctx: Context): String = p(ctx).getString("captureMode", "manual") ?: "manual"
    fun setCaptureMode(ctx: Context, mode: String) =
        p(ctx).edit().putString("captureMode", mode).apply()

    /** Records and ranks but never suggests a stake (tester phone). */
    fun observeOnly(ctx: Context): Boolean = p(ctx).getBoolean("observeOnly", false)
    fun setObserveOnly(ctx: Context, v: Boolean) =
        p(ctx).edit().putBoolean("observeOnly", v).apply()

    /** Pause captures (e.g. during a call). */
    fun capturePaused(ctx: Context): Boolean = p(ctx).getBoolean("capturePaused", false)
    fun setCapturePaused(ctx: Context, v: Boolean) =
        p(ctx).edit().putBoolean("capturePaused", v).apply()

    /** Minimum confidence (0–100) required before a stake is suggested. */
    fun minConfidence(ctx: Context): Int = p(ctx).getInt("minConfidence", 35)
    fun setMinConfidence(ctx: Context, v: Int) =
        p(ctx).edit().putInt("minConfidence", v.coerceIn(0, 100)).apply()

    /** Stake cap as percent of balance (default 7). */
    fun stakeCapPercent(ctx: Context): Int = p(ctx).getInt("stakeCapPercent", 7)
    fun setStakeCapPercent(ctx: Context, v: Int) =
        p(ctx).edit().putInt("stakeCapPercent", v.coerceIn(1, 20)).apply()

