package com.chamet.guesser

/**
 * Session 3 — timed round state machine (pure, no Android).
 *
 * States:
 *  IDLE / SAVED  → ready for next game
 *  BETTING       → cards + pools; capture once, rank, suggest
 *  CLOSED        → countdown on cards; wait for race start
 *  RACE          → ~15 s; strip once at start; top-view ~4 fps
 *  FINISH        → ~6 s; detect winner; save
 *
 * Timing (ms) is fixed defaults; tests can override via [Config].
 * Pause freezes the clock without losing the current phase or elapsed time.
 */
class RoundStateMachine(
    private val config: Config = Config(),
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    data class Config(
        val raceDurationMs: Long = 15_000L,
        val finishDurationMs: Long = 6_000L,
        /** Target top-view sample interval during RACE (~4 fps). */
        val trackIntervalMs: Long = 250L,
        /** Max time to stay in CLOSED waiting for race signal. */
        val closedTimeoutMs: Long = 90_000L
    )

    enum class Phase {
        IDLE,
        BETTING,
        CLOSED,
        RACE,
        FINISH,
        SAVED
    }

    enum class Event {
        /** User / auto start of a new betting window. */
        OPEN_BETTING,
        /** Cards locked / countdown visible. */
        BETTING_CLOSED,
        /** Race strip / start signal seen. */
        RACE_STARTED,
        /** Winner known or finish timeout. */
        WINNER_KNOWN,
        /** Round persisted. */
        ROUND_SAVED,
        /** External pause (call, screen off, background). */
        PAUSE,
        /** Resume after pause. */
        RESUME,
        /** Force reset without save (user cancel). */
        RESET,
        /** Countdown seconds read from screen (auto capture). */
        COUNTDOWN_TICK
    }

    /** What the capture layer should do right now. */
    enum class CaptureAction {
        NONE,
        /** Full pre-race: cards, pools, banner road. */
        FULL_PRE_RACE,
        /** One strip layout read at race start. */
        STRIP_ONCE,
        /** Top-view progress sample (~4 fps). */
        TRACK_SAMPLE,
        /** Finish-screen / winner check. */
        FINISH_CHECK
    }

    data class Snapshot(
        val phase: Phase,
        val phaseElapsedMs: Long,
        val raceElapsedMs: Long,
        val paused: Boolean,
        val action: CaptureAction,
        /** True once per RACE entry so strip is captured only once. */
        val stripPending: Boolean,
        val shouldSave: Boolean
    )

    private var phase: Phase = Phase.IDLE
    private var phaseStartedAt: Long = 0L
    private var raceStartedAt: Long = 0L
    private var paused: Boolean = false
    private var pausedAt: Long = 0L
    private var pauseAccumMs: Long = 0L
    private var stripCaptured: Boolean = false
    private var lastTrackAt: Long = 0L
    private var shouldSave: Boolean = false

    fun snapshot(): Snapshot {
        val t = effectiveNow()
        val elapsed = if (phaseStartedAt == 0L) 0L else (t - phaseStartedAt).coerceAtLeast(0L)
        val raceElapsed = if (raceStartedAt == 0L) 0L else (t - raceStartedAt).coerceAtLeast(0L)
        return Snapshot(
            phase = phase,
            phaseElapsedMs = elapsed,
            raceElapsedMs = raceElapsed,
            paused = paused,
            action = desiredAction(t, elapsed, raceElapsed),
            stripPending = phase == Phase.RACE && !stripCaptured && !paused,
            shouldSave = shouldSave
        )
    }

    fun phase(): Phase = phase
    fun isPaused(): Boolean = paused

    /**
     * Apply an external event. Returns the new snapshot.
     */
    fun onEvent(event: Event): Snapshot {
        when (event) {
            Event.PAUSE -> {
                if (!paused) {
                    paused = true
                    pausedAt = nowMs()
                }
            }
            Event.RESUME -> {
                if (paused) {
                    pauseAccumMs += (nowMs() - pausedAt).coerceAtLeast(0L)
                    paused = false
                    pausedAt = 0L
                }
            }
            Event.RESET -> hardReset()
            Event.OPEN_BETTING -> {
                if (phase == Phase.IDLE || phase == Phase.SAVED || phase == Phase.FINISH) {
                    enter(Phase.BETTING)
                    shouldSave = false
                    stripCaptured = false
                }
            }
            Event.BETTING_CLOSED -> {
                if (phase == Phase.BETTING) enter(Phase.CLOSED)
            }
            Event.RACE_STARTED -> {
                if (phase == Phase.BETTING || phase == Phase.CLOSED) {
                    enter(Phase.RACE)
                    raceStartedAt = effectiveNow()
                    stripCaptured = false
                    lastTrackAt = 0L
                }
            }
            Event.WINNER_KNOWN -> {
                if (phase == Phase.RACE || phase == Phase.FINISH) {
                    enter(Phase.FINISH)
                    shouldSave = true
                }
            }
            Event.ROUND_SAVED -> {
                enter(Phase.SAVED)
                shouldSave = false
            }
        }
        return snapshot()
    }

    /**
     * Tick the clock (call from a Handler ~ every 100–250 ms while active).
     * Auto-advances CLOSED timeout → RACE (if still waiting), RACE → FINISH,
     * FINISH → ready-to-save.
     */
    fun tick(): Snapshot {
        if (paused) return snapshot()
        val t = effectiveNow()
        val elapsed = if (phaseStartedAt == 0L) 0L else t - phaseStartedAt
        when (phase) {
            Phase.CLOSED -> {
                if (elapsed >= config.closedTimeoutMs) {
                    // No explicit race signal — still enter RACE so tracking can start
                    enter(Phase.RACE)
                    raceStartedAt = t
                    stripCaptured = false
                }
            }
            Phase.RACE -> {
                val raceElapsed = t - raceStartedAt
                if (raceElapsed >= config.raceDurationMs) {
                    enter(Phase.FINISH)
                    shouldSave = true
                }
            }
            Phase.FINISH -> {
                if (elapsed >= config.finishDurationMs) {
                    shouldSave = true
                }
            }
            else -> { /* idle / betting / saved */ }
        }
        return snapshot()
    }

    /**
     * Drive transitions from a countdown reading (seconds left on the cards).
     * Safe to call every tick while auto-capture is on.
     */
    fun applyCountdown(secondsLeft: Int?): Snapshot {
        if (secondsLeft == null || paused) return snapshot()
        val target = CountdownReader.suggestedPhase(secondsLeft) ?: return snapshot()
        when (target) {
            Phase.BETTING -> {
                if (phase == Phase.IDLE || phase == Phase.SAVED) {
                    onEvent(Event.OPEN_BETTING)
                }
            }
            Phase.CLOSED -> {
                if (phase == Phase.IDLE || phase == Phase.SAVED) onEvent(Event.OPEN_BETTING)
                if (phase == Phase.BETTING) onEvent(Event.BETTING_CLOSED)
            }
            Phase.RACE -> {
                if (phase == Phase.IDLE || phase == Phase.SAVED) onEvent(Event.OPEN_BETTING)
                if (phase == Phase.BETTING) onEvent(Event.BETTING_CLOSED)
                if (phase == Phase.CLOSED || phase == Phase.BETTING) onEvent(Event.RACE_STARTED)
            }
            Phase.FINISH -> {
                if (phase == Phase.RACE || phase == Phase.CLOSED) {
                    onEvent(Event.WINNER_KNOWN)
                }
            }
            else -> { }
        }
        return snapshot()
    }

    /** Mark that the strip was captured this race (so action becomes TRACK_SAMPLE). */
    fun markStripCaptured() {
        stripCaptured = true
        lastTrackAt = effectiveNow()
    }

    /** Mark that a track sample was taken (rate-limits to trackIntervalMs). */
    fun markTrackSampled() {
        lastTrackAt = effectiveNow()
    }

    fun markSaved() {
        onEvent(Event.ROUND_SAVED)
    }

    private fun desiredAction(t: Long, elapsed: Long, raceElapsed: Long): CaptureAction {
        if (paused) return CaptureAction.NONE
        return when (phase) {
            Phase.BETTING -> CaptureAction.FULL_PRE_RACE
            Phase.CLOSED -> CaptureAction.NONE
            Phase.RACE -> when {
                !stripCaptured -> CaptureAction.STRIP_ONCE
                lastTrackAt == 0L || (t - lastTrackAt) >= config.trackIntervalMs ->
                    CaptureAction.TRACK_SAMPLE
                else -> CaptureAction.NONE
            }
            Phase.FINISH -> CaptureAction.FINISH_CHECK
            Phase.IDLE, Phase.SAVED -> CaptureAction.NONE
        }
    }

    private fun enter(p: Phase) {
        phase = p
        phaseStartedAt = effectiveNow()
        if (p != Phase.RACE) {
            // raceStartedAt kept until next race
        }
        if (p == Phase.IDLE || p == Phase.SAVED || p == Phase.BETTING) {
            raceStartedAt = 0L
            stripCaptured = false
            lastTrackAt = 0L
        }
    }

    private fun hardReset() {
        phase = Phase.IDLE
        phaseStartedAt = 0L
        raceStartedAt = 0L
        paused = false
        pausedAt = 0L
        pauseAccumMs = 0L
        stripCaptured = false
        lastTrackAt = 0L
        shouldSave = false
    }

    /** Wall clock minus accumulated pause time. */
    private fun effectiveNow(): Long {
        val wall = nowMs()
        val extra = if (paused) (wall - pausedAt).coerceAtLeast(0L) else 0L
        return wall - pauseAccumMs - extra
    }

    companion object {
        fun toOverlayPhase(p: Phase): OverlayController.Phase = when (p) {
            Phase.IDLE, Phase.SAVED -> OverlayController.Phase.SAVED
            Phase.BETTING -> OverlayController.Phase.BETTING
            Phase.CLOSED -> OverlayController.Phase.CLOSED
            Phase.RACE -> OverlayController.Phase.RACE
            Phase.FINISH -> OverlayController.Phase.FINISH
        }
    }
}
