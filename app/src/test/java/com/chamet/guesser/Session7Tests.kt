package com.chamet.guesser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.system.measureTimeMillis

/**
 * Phase 1 / Session 7 — finish-time model + hidden-layout simulation.
 */
class Session7Tests {

    @org.junit.Before
    fun resetParams() = EngineParams.resetToDefault()

    // ---- golden exact times (must always pass) ----

    @Test fun golden_4_11() {
        val segs = listOf("Desert" to 870.0, "Highway" to 118.0)
        val r = Guesser.exactGuess(
            listOf("SUV", "Monster Truck", "Sports Car"), segs
        )!!
        assertEquals("SUV", r.ranked[0].car)
        assertNear(16.1, r.ranked.first { it.car == "SUV" }.meanTime, 0.05)
        assertNear(17.0, r.ranked.first { it.car == "Monster Truck" }.meanTime, 0.05)
        assertNear(19.8, r.ranked.first { it.car == "Sports Car" }.meanTime, 0.05)
        assertTrue(r.layoutKnown)
    }

    @Test fun golden_4_14() {
        val segs = listOf(
            "Highway" to 167.0, "Expressway" to 98.0, "Desert" to 722.0
        )
        val r = Guesser.exactGuess(
            listOf("Motorcycle", "Monster Truck", "Supercar"), segs
        )!!
        assertEquals("Motorcycle", r.ranked[0].car)
        assertNear(14.2, r.ranked.first { it.car == "Motorcycle" }.meanTime, 0.05)
        assertNear(15.6, r.ranked.first { it.car == "Monster Truck" }.meanTime, 0.05)
        assertNear(18.9, r.ranked.first { it.car == "Supercar" }.meanTime, 0.05)
    }

    @Test fun golden_4_16() {
        val segs = listOf(
            "Desert" to 98.0, "Bumpy" to 415.0, "Desert" to 475.0
        )
        val r = Guesser.exactGuess(
            listOf("Motorcycle", "Car", "Supercar"), segs
        )!!
        assertEquals("Motorcycle", r.ranked[0].car)
        assertNear(14.2, r.ranked.first { it.car == "Motorcycle" }.meanTime, 0.05)
        assertNear(17.0, r.ranked.first { it.car == "Car" }.meanTime, 0.05)
        assertNear(21.2, r.ranked.first { it.car == "Supercar" }.meanTime, 0.05)
    }

    // ---- simulation determinism ----

    @Test fun sameSeedSameOutput() {
        val cars = listOf("Supercar", "ATV", "Car")
        val a = Guesser.simulateGuess("Highway", cars, seed = 42L, simulations = 500)!!
        val b = Guesser.simulateGuess("Highway", cars, seed = 42L, simulations = 500)!!
        assertEquals(a.ranked.map { it.car }, b.ranked.map { it.car })
        for (i in a.ranked.indices) {
            assertEquals(a.ranked[i].winProb, b.ranked[i].winProb, 1e-12)
            assertEquals(a.ranked[i].top2Prob, b.ranked[i].top2Prob, 1e-12)
        }
    }

    @Test fun differentSeedsDifferUnderOnePercent() {
        val cars = listOf("Supercar", "ATV", "Car")
        val a = Guesser.simulateGuess("Desert", cars, seed = 1L, simulations = 4000)!!
        val b = Guesser.simulateGuess("Desert", cars, seed = 2L, simulations = 4000)!!
        for (car in cars) {
            val pa = a.ranked.first { it.car == car }.winProb
            val pb = b.ranked.first { it.car == car }.winProb
            assertTrue(
                "P(win) for $car differed by ${abs(pa - pb)} (>0.02)",
                abs(pa - pb) < 0.01 // plan: under ~1% of P(win)
            )
        }
        // At least one car can differ slightly
        val maxDiff = cars.maxOf { car ->
            abs(
                a.ranked.first { it.car == car }.winProb -
                    b.ranked.first { it.car == car }.winProb
            )
        }
        // with 2000 sims diffs should usually be small
        assertTrue(maxDiff < 0.015)
    }

    @Test fun winProbSumsToOne() {
        val r = Guesser.simulateGuess(
            "Bumpy", listOf("ATV", "Car", "SUV"), seed = 7L, simulations = 1000
        )!!
        assertEquals(1.0, r.ranked.sumOf { it.winProb }, 1e-9)
        r.ranked.forEach {
            assertTrue(it.top2Prob + 1e-9 >= it.winProb)
            assertTrue(it.top2Prob <= 1.0 + 1e-9)
        }
    }

    @Test fun fasterCarFavouredOnHighway() {
        val r = Guesser.simulateGuess(
            "Highway", listOf("Supercar", "ATV"), seed = 99L, simulations = 1500
        )!!
        assertEquals("Supercar", r.ranked[0].car)
        assertTrue(r.ranked[0].winProb > r.ranked[1].winProb)
    }

    @Test fun simulationUnderFiftyMs() {
        val cars = listOf("Supercar", "Car", "ATV")
        // warm-up
        Guesser.simulateGuess("Desert", cars, seed = 1L, simulations = 2000)
        val ms = measureTimeMillis {
            Guesser.simulateGuess("Desert", cars, seed = 2L, simulations = 2000)
        }
        println("Session7 sim 2000 took ${ms}ms (phone target ~50ms)")
        // CI limit generous; phone target ~50ms
        assertTrue("sim took ${ms}ms", ms < 500)
    }

    // ---- params ----

    @Test fun paramsVersionAndSpeeds() {
        assertEquals("8.1.0", EngineParams.DEFAULT.version)
        assertEquals(9, EngineParams.DEFAULT.speeds.size)
        assertEquals(320, EngineParams.DEFAULT.speedOf("Supercar", "Highway"))
        assertEquals(SpeedDatabase.paramsVersion, EngineParams.active.version)
    }

    @Test fun sampleLayoutIncludesVisibleRoad() {
        val rng = SeededRng(123L)
        val params = EngineParams.DEFAULT
        repeat(50) {
            val layout = Guesser.sampleLayout(
                "Desert", params.familyOf("Desert"), 990.0, params, rng
            )
            assertTrue(layout.any { it.first == "Desert" })
            assertEquals(990.0, layout.sumOf { it.second }, 0.5)
            assertTrue(layout.size in 2..3)
        }
    }

    @Test fun guessWithKnownSegmentsIsExact() {
        val segs = listOf("Desert" to 870.0, "Highway" to 118.0)
        val r = Guesser.guess(
            "Desert",
            listOf("SUV", "Monster Truck", "Sports Car"),
            knownSegments = segs
        )
        assertNotNull(r)
        assertTrue(r!!.layoutKnown)
        assertEquals("SUV", r.ranked[0].car)
        assertEquals(1.0, r.ranked[0].winProb, 1e-9)
    }

    private fun assertNear(expected: Double, actual: Double, tol: Double) {
        assertTrue(
            "expected $expected ±$tol, got $actual",
            abs(expected - actual) <= tol
        )
    }
}
