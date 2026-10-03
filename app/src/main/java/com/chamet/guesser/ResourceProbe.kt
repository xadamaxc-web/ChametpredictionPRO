package com.chamet.guesser

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log

/**
 * Session 5 — lightweight resource samples for a 1-hour observe run.
 * Records process memory; CPU is sampled via rough uptime deltas (not perfect).
 * Numbers must be filled from a real device run — code only collects.
 */
object ResourceProbe {

    data class Sample(
        val elapsedMs: Long,
        val totalPssKb: Int,
        val nativeHeapKb: Long,
        val note: String = ""
    )

    private val samples = mutableListOf<Sample>()
    private var startedAt = 0L

    fun start() {
        samples.clear()
        startedAt = SystemClock.elapsedRealtime()
    }

    fun snapshot(context: Context, note: String = ""): Sample {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        var pss = 0
        try {
            val info = android.os.Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            pss = info.totalPss
        } catch (_: Exception) { }
        val native = Debug.getNativeHeapAllocatedSize() / 1024
        val s = Sample(
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            totalPssKb = pss,
            nativeHeapKb = native,
            note = note
        )
        synchronized(samples) {
            samples.add(s)
            if (samples.size > 500) samples.removeAt(0)
        }
        Log.i("ResourceProbe", "pss=${s.totalPssKb}kb native=${s.nativeHeapKb}kb ${s.note}")
        return s
    }

    fun all(): List<Sample> = synchronized(samples) { samples.toList() }

    fun reportText(): String {
        val list = all()
        if (list.isEmpty()) return "ResourceProbe: no samples (unverified)"
        val maxPss = list.maxOf { it.totalPssKb }
        val avgPss = list.map { it.totalPssKb }.average()
        return buildString {
            appendLine("=== Resource probe ===")
            appendLine("samples=${list.size}  maxPss=${maxPss}kb  avgPss=${"%.0f".format(avgPss)}kb")
            appendLine("(CPU% and battery require a physical 1h run — unverified here)")
            list.takeLast(5).forEach {
                appendLine("  t=${it.elapsedMs}ms pss=${it.totalPssKb}kb ${it.note}")
            }
        }
    }
}
