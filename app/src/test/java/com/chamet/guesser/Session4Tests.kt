package com.chamet.guesser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Session4Tests {
    private val advice = OddsEngine.SplitAdvice(
        cars = listOf(
            OddsEngine.CarOdds("ATV", 1000, 4.0, 1.2, 600),
            OddsEngine.CarOdds("Car", 2000, 2.0, 1.1, 400),
            OddsEngine.CarOdds("SUV", 3000, 1.3, 0.5, 0)
        ),
        totalBet = 1000, bet1 = 600, car1 = "ATV", bet2 = 400, car2 = "Car",
        rank1 = 1, rank2 = 2, warning = null, topEvIndex = 0
    )

    @Test fun backedCarsAreTheSuggestedOnes() {
        assertTrue(OddsEngine.isBacked(advice, "ATV"))
        assertTrue(OddsEngine.isBacked(advice, "Car"))
        assertFalse(OddsEngine.isBacked(advice, "SUV"))
    }

    @Test fun noAdviceBacksNothing() {
        assertFalse(OddsEngine.isBacked(null, "ATV"))
        assertFalse(OddsEngine.isBacked(advice, null))
    }

    @Test fun tappingUnbackedWinnerIsALossButWinnerIsKnown() =
        assertEquals(-1000L, OddsEngine.net(advice, "SUV"))

    @Test fun tappingSecondPickPaysSecondBet() =
        assertEquals(400 * 2 - 1000L, OddsEngine.net(advice, "Car"))
}
