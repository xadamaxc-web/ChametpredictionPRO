
package com.chamet.guesser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CountdownReaderTests {

    @Test fun parsesCommonFormats() {
        assertEquals(27, CountdownReader.parseSeconds("27s"))
        assertEquals(27, CountdownReader.parseSeconds("Time 27 s left"))
        assertEquals(6, CountdownReader.parseSeconds("6S"))
        assertEquals(0, CountdownReader.parseSeconds("0s"))
    }

    @Test fun ignoresPoolSizedNumbers() {
        // Prefer smaller countdown when multiple match
        assertEquals(12, CountdownReader.parseSeconds("pool 3327187  12s"))
    }

    @Test fun nullOnGarbage() {
        assertNull(CountdownReader.parseSeconds(null))
        assertNull(CountdownReader.parseSeconds(""))
        assertNull(CountdownReader.parseSeconds("Highway Desert"))
    }

    @Test fun phaseFromSeconds() {
        assertEquals(RoundStateMachine.Phase.BETTING, CountdownReader.suggestedPhase(35))
        assertEquals(RoundStateMachine.Phase.CLOSED, CountdownReader.suggestedPhase(25))
        assertEquals(RoundStateMachine.Phase.RACE, CountdownReader.suggestedPhase(15))
        assertEquals(RoundStateMachine.Phase.RACE, CountdownReader.suggestedPhase(10))
        assertEquals(RoundStateMachine.Phase.FINISH, CountdownReader.suggestedPhase(6))
        assertEquals(RoundStateMachine.Phase.FINISH, CountdownReader.suggestedPhase(0))
    }

    @Test fun cropBoxFractions() {
        val box = CountdownReader.cropBox(1080, 1920)
        assertTrue(box.width > 0 && box.height > 0)
        assertTrue(box.left + box.width <= 1080)
        assertTrue(box.top + box.height <= 1920)
    }

    @Test fun applyCountdownAdvancesMachine() {
        var clock = 0L
        val m = RoundStateMachine(nowMs = { clock })
        m.onEvent(RoundStateMachine.Event.OPEN_BETTING)
        m.applyCountdown(25)
        assertEquals(RoundStateMachine.Phase.CLOSED, m.phase())
        m.applyCountdown(12)
        assertEquals(RoundStateMachine.Phase.RACE, m.phase())
        m.applyCountdown(3)
        assertEquals(RoundStateMachine.Phase.FINISH, m.phase())
    }
}
