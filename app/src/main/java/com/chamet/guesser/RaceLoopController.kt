package com.chamet.guesser

/**
 * Session 3 — rate-limits race-phase work so the overlay stays light.
 *
 * - Strip: once at race start + optional re-check ~1 s later.
 * - Track samples: ~4 fps while in RACE only.
 * - No per-frame OCR.
 *
 * Pure timing helper; OverlayService owns the actual captures.
 */
class RaceLoopController(
    private val trackIntervalMs: Long = 250L,
    private val stripRecheckAfterMs: Long = 1_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private var raceStartMs: Long = 0L
    private var lastTrackMs: Long = 0L
    private var stripDone = false
    private var stripRecheckDone = false
    private var sampleCount = 0

    fun onRaceStarted() {
        raceStartMs = nowMs()
        lastTrackMs = 0L
        stripDone = false
        stripRecheckDone = false
        sampleCount = 0
    }

    fun onRaceEnded() {
        raceStartMs = 0L
    }

    enum class Work { NONE, STRIP, STRIP_RECHECK, TRACK }

    fun nextWork(inRace: Boolean): Work {
        if (!inRace || raceStartMs == 0L) return Work.NONE
        val t = nowMs()
        val elapsed = t - raceStartMs
        if (!stripDone) {
            stripDone = true
            return Work.STRIP
        }
        if (!stripRecheckDone && elapsed >= stripRecheckAfterMs) {
            stripRecheckDone = true
            return Work.STRIP_RECHECK
        }
        if (lastTrackMs == 0L || t - lastTrackMs >= trackIntervalMs) {
            lastTrackMs = t
            sampleCount++
            return Work.TRACK
        }
        return Work.NONE
    }

    fun samplesTaken(): Int = sampleCount

    /** Expected samples over [raceDurationMs] at trackIntervalMs. */
    fun expectedSamples(raceDurationMs: Long = 15_000L): Int =
        ((raceDurationMs / trackIntervalMs).toInt()).coerceAtLeast(1)
}
