package com.chamet.guesser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 / Session 3 — timed round state machine.
 */
class Session3Tests {

    private var clock = 1_000L  // non-zero: 0 is the "unset" sentinel in the machine
    private fun machine(
        raceMs: Long = 15_000L,
        finishMs: Long = 6_000L,
        trackMs: Long = 250L,
        closedMs: Long = 90_000L
    ) = RoundStateMachine(
        config = RoundStateMachine.Config(raceMs, finishMs, trackMs, closedMs),
        nowMs = { clock }
    )

    @Test fun startsIdle() {
        val m = machine()
        assertEquals(RoundStateMachine.Phase.IDLE, m.phase())
        assertEquals(RoundStateMachine.CaptureAction.NONE, m.snapshot().action)
    }

    @Test fun openBettingThenClosedThenRace() {
        val m = machine()
        m.onEvent(RoundStateMachine.Event.OPEN_BETTING)
        assertEquals(RoundStateMachine.Phase.BETTING, m.phase())
        assertEquals(RoundStateMachine.CaptureAction.FULL_PRE_RACE, m.snapshot().action)

        m.onEvent(RoundStateMachine.Event.BETTING_CLOSED)
        assertEquals(RoundStateMachine.Phase.CLOSED, m.phase())
        assertEquals(RoundStateMachine.CaptureAction.NONE, m.snapshot().action)

        m.onEvent(RoundStateMachine.Event.RACE_STARTED)
        assertEquals(RoundStateMachine.Phase.RACE, m.phase())
        assertTrue(m.snapshot().stripPending)
        assertEquals(RoundStateMachine.CaptureAction.STRIP_ONCE, m.snapshot().action)
    }

    @Test fun stripThenTrackSamplesAtFourFps() {
        val m = machine(trackMs = 250L)
        m.onEvent(RoundStateMachine.Event.OPEN_BETTING)
        m.onEvent(RoundStateMachine.Event.RACE_STARTED)
        assertEquals(RoundStateMachine.CaptureAction.STRIP_ONCE, m.snapshot().action)
        m.markStripCaptured()
        assertEquals(RoundStateMachine.CaptureAction.NONE, m.snapshot().action)

        clock += 250
        assertEquals(RoundStateMachine.CaptureAction.TRACK_SAMPLE, m.snapshot().action)
        m.markTrackSampled()
        assertEquals(RoundStateMachine.CaptureAction.NONE, m.snapshot().action)

        clock += 249
        assertEquals(RoundStateMachine.CaptureAction.NONE, m.snapshot().action)
        clock += 1
        assertEquals(RoundStateMachine.CaptureAction.TRACK_SAMPLE, m.snapshot().action)
    }

    @Test fun raceAutoAdvancesToFinishAfter15s() {
        val m = machine(raceMs = 15_000L, finishMs = 6_000L)
        m.onEvent(RoundStateMachine.Event.OPEN_BETTING)
        m.onEvent(RoundStateMachine.Event.RACE_STARTED)
        m.markStripCaptured()
        clock += 14_999
        m.tick()
        assertEquals(RoundStateMachine.Phase.RACE, m.phase())
        clock += 1
        m.tick()
        assertEquals(RoundStateMachine.Phase.FINISH, m.phase())
        assertTrue(m.snapshot().shouldSave)
        assertEquals(RoundStateMachine.CaptureAction.FINISH_CHECK, m.snapshot().action)
    }

    @Test fun closedTimeoutEntersRace() {
        val m = machine(closedMs = 1_000L)
        m.onEvent(RoundStateMachine.Event.OPEN_BETTING)
        m.onEvent(RoundStateMachine.Event.BETTING_CLOSED)
        clock += 1_000
        m.tick()
        assertEquals(RoundStateMachine.Phase.RACE, m.phase())
    }

    @Test fun pauseFreezesElapsed() {
        val m = machine(raceMs = 15_000L)
        m.onEvent(RoundStateMachine.Event.OPEN_BETTING)
        m.onEvent(RoundStateMachine.Event.RACE_STARTED)
        m.markStripCaptured()
        clock += 5_000
        m.onEvent(RoundStateMachine.Event.PAUSE)
        assertTrue(m.isPaused())
        assertEquals(RoundStateMachine.CaptureAction.NONE, m.snapshot().action)
        clock += 20_000 // would have finished if not paused
        m.tick()
        assertEquals(RoundStateMachine.Phase.RACE, m.phase())
        m.onEvent(RoundStateMachine.Event.RESUME)
        assertFalse(m.isPaused())
        clock += 10_000
        m.tick()
        assertEquals(RoundStateMachine.Phase.FINISH, m.phase())
    }

    @Test fun winnerKnownAndSaved() {
        val m = machine()
        m.onEvent(RoundStateMachine.Event.OPEN_BETTING)
        m.onEvent(RoundStateMachine.Event.RACE_STARTED)
        m.onEvent(RoundStateMachine.Event.WINNER_KNOWN)
        assertEquals(RoundStateMachine.Phase.FINISH, m.phase())
        assertTrue(m.snapshot().shouldSave)
        m.markSaved()
        assertEquals(RoundStateMachine.Phase.SAVED, m.phase())
        assertFalse(m.snapshot().shouldSave)
    }

    @Test fun resetClearsToIdle() {
        val m = machine()
        m.onEvent(RoundStateMachine.Event.OPEN_BETTING)
        m.onEvent(RoundStateMachine.Event.RACE_STARTED)
        m.onEvent(RoundStateMachine.Event.RESET)
        assertEquals(RoundStateMachine.Phase.IDLE, m.phase())
    }

    @Test fun mapsToOverlayPhases() {
        assertEquals(
            OverlayController.Phase.BETTING,
            RoundStateMachine.toOverlayPhase(RoundStateMachine.Phase.BETTING)
        )
        assertEquals(
            OverlayController.Phase.RACE,
            RoundStateMachine.toOverlayPhase(RoundStateMachine.Phase.RACE)
        )
    }

    @Test fun secondOpenAfterSavedWorks() {
        val m = machine()
        m.onEvent(RoundStateMachine.Event.OPEN_BETTING)
        m.onEvent(RoundStateMachine.Event.RACE_STARTED)
        m.onEvent(RoundStateMachine.Event.WINNER_KNOWN)
        m.markSaved()
        m.onEvent(RoundStateMachine.Event.OPEN_BETTING)
        assertEquals(RoundStateMachine.Phase.BETTING, m.phase())
    }
}
