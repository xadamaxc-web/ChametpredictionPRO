package com.chamet.guesser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Phase 1 / Session 5 — top-view tracker unit tests.
 * Lane order = card order; finish by front-edge progress; speed-drift flag.
 */
class Session5TrackTests {

    private fun state(
        cars: List<String> = listOf("Motorcycle", "Car", "Supercar"),
        start: Float = 0f,
        finish: Float = 990f
    ) = TopViewTracker.TrackState(
        laneCars = cars,
        samples = emptyList(),
        startX = start,
        finishX = finish,
        trackWidth = finish - start
    )

    // ---- lane assignment ----

    @Test fun laneOrderMatchesCards() {
        val lanes = TopViewTracker.assignLanes(listOf("ATV", "Car", "SUV"))
        assertEquals(listOf("ATV", "Car", "SUV"), lanes)
    }

    @Test fun colourHintFillsEmptyLaneOnly() {
        val lanes = TopViewTracker.assignLanes(
            listOf("ATV", "—", "SUV"),
            colourHints = listOf(null, "Car", "Motorcycle")
        )
        // mid filled from hint; right keeps card (SUV) over hint
        assertEquals(listOf("ATV", "Car", "SUV"), lanes)
    }

    @Test fun encodeDecodeLaneCars() {
        val s = TopViewTracker.encodeLaneCars(listOf("ATV", "Car", "SUV"))
        assertEquals("ATV|Car|SUV", s)
        assertEquals(listOf("ATV", "Car", "SUV"), TopViewTracker.decodeLaneCars(s))
        assertEquals(listOf("—", "—", "—"), TopViewTracker.decodeLaneCars(null))
    }

    // ---- sampling ----

    @Test fun appendKeepsAtMostMaxSamples() {
        var samples = emptyList<TopViewTracker.Sample>()
        for (i in 0 until 250) {
            samples = TopViewTracker.appendSample(
                samples,
                TopViewTracker.Sample(i * 50L, i.toFloat(), i * 0.9f, i * 0.8f)
            )
        }
        assertTrue(samples.size <= TopViewTracker.MAX_SAMPLES)
        assertEquals(0L, samples.first().timeMs)
        assertTrue(samples.last().timeMs >= 200 * 50L)
    }

    @Test fun entitiesRoundTrip() {
        val samples = listOf(
            TopViewTracker.Sample(0, 10f, 20f, 30f),
            TopViewTracker.Sample(250, 50f, 60f, 70f)
        )
        val ents = TopViewTracker.toEntities("uuid-1", samples)
        assertEquals(2, ents.size)
        assertEquals("uuid-1", ents[0].roundUuid)
        val back = TopViewTracker.fromEntities(ents)
        assertEquals(samples, back)
    }

    // ---- finish detection ----

    @Test fun firstToFinishIsWinner() {
        val st = state(listOf("Motorcycle", "Car", "Supercar"))
        // Motorcycle pulls ahead and crosses first
        val samples = listOf(
            TopViewTracker.Sample(0, 0f, 0f, 0f),
            TopViewTracker.Sample(1000, 500f, 400f, 300f),
            TopViewTracker.Sample(2000, 980f, 700f, 500f),  // Moto done
            TopViewTracker.Sample(3000, 990f, 980f, 700f),  // Car done
            TopViewTracker.Sample(4000, 990f, 990f, 980f)   // Super done
        )
        val fin = TopViewTracker.detectFinish(st, samples)
        assertEquals("Motorcycle", fin.winner)
        assertEquals(listOf("Motorcycle", "Car", "Supercar"), fin.order)
        assertTrue(fin.allFinished)
        assertEquals(2000L, fin.finishTimesMs["Motorcycle"])
    }

    @Test fun unfinishedRaceHasPartialOrder() {
        val st = state()
        val samples = listOf(
            TopViewTracker.Sample(0, 0f, 0f, 0f),
            TopViewTracker.Sample(1000, 990f, 200f, 100f)
        )
        val fin = TopViewTracker.detectFinish(st, samples)
        assertEquals(listOf("Motorcycle"), fin.order)
        assertFalse(fin.allFinished)
    }

    @Test fun encodeFinishOrder() {
        assertEquals("ATV|Car|SUV", TopViewTracker.encodeFinishOrder(listOf("ATV", "Car", "SUV")))
    }

    // ---- progress ----

    @Test fun progressUsesFrontEdge() {
        val st = state(start = 60f, finish = 1050f)
        val mid = TopViewTracker.Sample(0, 555f, 555f, 555f) // halfway of 990 span
        val p = st.progress(mid, 1)
        assertTrue(abs(p - 0.5f) < 0.02f)
    }

    @Test fun progressSnapshotThreeLanes() {
        val st = state()
        val s = TopViewTracker.Sample(0, 495f, 0f, 990f)
        val snap = TopViewTracker.progressSnapshot(st, s)
        assertEquals(3, snap.size)
        assertTrue(snap[0] in 0.4f..0.6f)
        assertTrue(snap[1] < 0.05f)
        assertTrue(snap[2] >= 0.98f)
    }

    // ---- speed drift ----

    @Test fun equalMotionNoDrift() {
        // All three cars move at rates proportional to their table speeds on Desert
        // Motorcycle 63, Car 50, Supercar 40 → distances over 10s should be proportional
        val cars = listOf("Motorcycle", "Car", "Supercar")
        val st = state(cars)
        val t0 = 0L
        val t1 = 10_000L
        // scale constant k = 1.0 → px = speed * time_sec
        val s0 = TopViewTracker.Sample(t0, 0f, 0f, 0f)
        val s1 = TopViewTracker.Sample(
            t1,
            63f * 10,   // Motorcycle
            50f * 10,   // Car
            40f * 10    // Supercar
        )
        val roads = listOf("Desert" to 990.0)
        val d = TopViewTracker.checkSpeedDrift(st, listOf(s0, s1), roads, cars)
        assertFalse(d.driftDetected)
        assertNull(d.flagMessage)
        assertNotNull(d.medianScale)
    }

    @Test fun oneCarOffTriggersDrift() {
        val cars = listOf("Motorcycle", "Car", "Supercar")
        val st = state(cars)
        val s0 = TopViewTracker.Sample(0, 0f, 0f, 0f)
        // Supercar travels as if speed were 2× table → clear drift
        val s1 = TopViewTracker.Sample(10_000, 63f * 10, 50f * 10, 40f * 10 * 2)
        val d = TopViewTracker.checkSpeedDrift(st, listOf(s0, s1), listOf("Desert" to 990.0), cars)
        assertTrue(d.driftDetected)
        assertNotNull(d.flagMessage)
        assertTrue(d.flagMessage!!.contains("Supercar") || d.flagMessage!!.contains("drift"))
    }

    // ---- sprite colour hints ----

    @Test fun spriteHintsRecogniseKnownColours() {
        assertEquals("Motorcycle", TopViewTracker.nearestSpriteHint(40, 160, 60))
        assertEquals("Car", TopViewTracker.nearestSpriteHint(220, 100, 140))
        assertEquals("Sports Car", TopViewTracker.nearestSpriteHint(40, 90, 200))
        assertNull(TopViewTracker.nearestSpriteHint(0, 0, 0, maxDist = 20.0))
    }

    @Test fun geometryNormalisesStartFinish() {
        val (lo, hi, w) = TopViewTracker.geometry(1050f, 60f)
        assertEquals(60f, lo)
        assertEquals(1050f, hi)
        assertEquals(990f, w)
    }
}
