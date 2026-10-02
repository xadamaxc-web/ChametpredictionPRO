package com.chamet.guesser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Session2Tests {

    private fun advice(winProb: Map<String, Double> = emptyMap()) = OddsEngine.compute(
        cars = listOf("ATV", "Car", "Stock Car"),
        poolByCar = mapOf("ATV" to 1_000_000L, "Car" to 1_000_000L, "Stock Car" to 1_000_000L),
        confidence = 80, balance = 100_000L, winProb = winProb
    )

    @Test fun winChanceComesFromScoresNotFixedRankWeights() {
        // Equal pools: whoever the model likes most has the best EV, even if it is the 3rd listed car.
        val a = advice(mapOf("ATV" to 0.2, "Car" to 0.2, "Stock Car" to 0.6))
        assertEquals(2, a.topEvIndex)
        assertEquals("Stock Car", a.car1)
        // EV = odds (3.0) * 0.6
        assertEquals(1.8, a.cars[2].ev, 1e-9)
    }

    @Test fun noScoresMeansEqualChance() {
        val a = advice()
        a.cars.forEach { assertEquals(1.0, it.ev, 1e-9) }
    }

    @Test fun guesserWinProbSumsToOneAndFavoursFasterCar() {
        val r = Guesser.guess("Highway", listOf("Supercar", "ATV", "Car"))!!
        assertEquals(1.0, r.ranked.sumOf { it.winProb }, 1e-9)
        assertEquals("Supercar", r.ranked[0].car)
        assertTrue(r.ranked[0].winProb > r.ranked[2].winProb)
    }

    @Test fun oddsAgreementIsRealNotConstant() {
        val cars = listOf("A", "B", "C")
        assertEquals(1.0, Guesser.oddsAgreement("A", cars, mapOf("A" to 300L, "B" to 200L, "C" to 100L))!!, 1e-9)
        assertEquals(0.5, Guesser.oddsAgreement("A", cars, mapOf("A" to 200L, "B" to 300L, "C" to 100L))!!, 1e-9)
        assertEquals(0.0, Guesser.oddsAgreement("A", cars, mapOf("A" to 100L, "B" to 300L, "C" to 200L))!!, 1e-9)
        assertNull(Guesser.oddsAgreement("A", cars, emptyMap()))
    }

    @Test fun confidenceWithoutOddsRescalesInsteadOfAssuming60() {
        // gap 0.5, sample 1.0 -> (0.275 + 0.15) / 0.70
        assertEquals(60, Guesser.confidenceOf(0.5, 1.0, null))
        assertEquals(Guesser.confidenceOf(0.5, 1.0, 1.0) > Guesser.confidenceOf(0.5, 1.0, 0.0), true)
    }

    // ---- payout by the car that really won ----
    private fun split(): OddsEngine.SplitAdvice {
        val cars = listOf(
            OddsEngine.CarOdds("ATV", 1, 4.0, 1.4, 600),
            OddsEngine.CarOdds("Car", 1, 2.0, 1.1, 400),
            OddsEngine.CarOdds("Stock Car", 1, 3.0, 0.8, 0)
        )
        return OddsEngine.SplitAdvice(cars, 1000, 600, "ATV", 400, "Car", 1, 2, null, 0)
    }

    @Test fun firstPickWins() = assertEquals(600 * 4 - 1000L, OddsEngine.net(split(), "ATV"))
    @Test fun secondPickWinsPaysSecondBetNotFirst() = assertEquals(400 * 2 - 1000L, OddsEngine.net(split(), "Car"))
    @Test fun unbackedCarWinsIsLoss() = assertEquals(-1000L, OddsEngine.net(split(), "Stock Car"))
    @Test fun lostIsStakeGone() = assertEquals(-1000L, OddsEngine.net(split(), null))
    @Test fun noAdviceNoChange() = assertEquals(0L, OddsEngine.net(null, "ATV"))

    // ---- speed table shape (values themselves are NOT verified here) ----
    @Test fun speedTableIsComplete() {
        assertEquals(9, SpeedDatabase.CAR_SPEEDS.size)
        SpeedDatabase.CAR_SPEEDS.forEach { (car, m) ->
            assertEquals(car, SpeedDatabase.ROADS.toSet(), m.keys)
            m.values.forEach { assertTrue("$car has a non-positive speed", it > 0) }
        }
        SpeedDatabase.ROAD_FAMILIES.values.forEach { fam ->
            assertNotNull(fam); assertTrue(SpeedDatabase.ROADS.containsAll(fam))
        }
    }
}
