package com.chamet.guesser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 / Session 9 — overlay controller (light, pure helpers).
 */
class Session9Tests {

    @Test fun phaseLabelsAndColorsDistinct() {
        val phases = OverlayController.Phase.values()
        val colors = phases.map { OverlayController.phaseColor(it) }.toSet()
        val labels = phases.map { OverlayController.phaseLabel(it) }.toSet()
        assertEquals(5, colors.size)
        assertEquals(5, labels.size)
        phases.forEach {
            assertTrue(OverlayController.phaseLabel(it).startsWith("●"))
            assertTrue(OverlayController.phaseColor(it).startsWith("#"))
        }
    }

    @Test fun inferPhaseFromFlags() {
        assertEquals(
            OverlayController.Phase.BETTING,
            OverlayController.inferPhase(false, false, false, null)
        )
        assertEquals(
            OverlayController.Phase.CLOSED,
            OverlayController.inferPhase(true, false, false, null)
        )
        assertEquals(
            OverlayController.Phase.RACE,
            OverlayController.inferPhase(true, false, true, "race")
        )
        assertEquals(
            OverlayController.Phase.FINISH,
            OverlayController.inferPhase(true, false, true, "finish")
        )
        assertEquals(
            OverlayController.Phase.SAVED,
            OverlayController.inferPhase(true, true, true, "finish")
        )
    }

    @Test fun glanceLinePickBandStake() {
        val line = OverlayController.glanceLine(
            car1 = "ATV", bet1 = 7000, confidenceLabel = "HIGH", confidence = 80,
            observeOnly = false
        )
        assertTrue(line.contains("ATV"))
        assertTrue(line.contains("HIGH"))
        assertTrue(line.contains("7,000") || line.contains("7000"))
    }

    @Test fun glanceObserveOnlyZeroStake() {
        val line = OverlayController.glanceLine(
            car1 = "ATV", bet1 = 7000, confidenceLabel = "MEDIUM", confidence = 60,
            observeOnly = true
        )
        assertTrue(line.contains("observe"))
        assertTrue(line.contains("0"))
        assertFalse(line.contains("7,000"))
    }

    @Test fun glanceAddsModelOrderWhenLayoutKnown() {
        val line = OverlayController.glanceLine(
            car1 = "SUV", bet1 = 0, confidenceLabel = "HIGH", confidence = 90,
            observeOnly = true,
            modelOrder = listOf("SUV", "Monster Truck", "Sports Car"),
            layoutKnown = true
        )
        assertTrue(line.contains("Model:"))
        assertTrue(line.contains("SUV"))
        assertTrue(line.contains("Monster Truck"))
    }

    @Test fun stakeAllowedRespectsThresholdAndObserve() {
        assertFalse(OverlayController.stakeAllowed(50, 60, false))
        assertTrue(OverlayController.stakeAllowed(70, 60, false))
        assertFalse(OverlayController.stakeAllowed(90, 0, true))
    }

    @Test fun filterAdviceZerosBetsInObserveMode() {
        val cars = listOf(
            OddsEngine.CarOdds("ATV", 100_000, 4.0, 2.0, 5000),
            OddsEngine.CarOdds("Car", 200_000, 2.0, 1.0, 0)
        )
        val advice = OddsEngine.SplitAdvice(
            cars, 5000, 5000, "ATV", 0, null, 1, 0, null, 0
        )
        val filtered = OverlayController.filterAdvice(advice, 80, 35, observeOnly = true)!!
        assertEquals(0, filtered.totalBet)
        assertEquals(0, filtered.bet1)
        assertTrue(filtered.warning!!.contains("Observe"))
        assertTrue(filtered.cars.all { it.suggestedBet == 0 })
    }

    @Test fun filterAdviceBelowMinConfidence() {
        val cars = listOf(OddsEngine.CarOdds("ATV", 100_000, 4.0, 2.0, 5000))
        val advice = OddsEngine.SplitAdvice(cars, 5000, 5000, "ATV", 0, null, 1, 0, null, 0)
        val filtered = OverlayController.filterAdvice(advice, 20, 35, observeOnly = false)!!
        assertEquals(0, filtered.totalBet)
        assertTrue(filtered.warning!!.contains("threshold"))
    }
}
