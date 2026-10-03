package com.chamet.guesser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ParamsLoaderTests {

    @Before fun reset() = EngineParams.resetToDefault()

    private val sampleJson = """
    {
      "schema_version": 1,
      "version": "8.1.test",
      "simulations": 500,
      "defaultSeed": 7,
      "roads": ["Highway", "Desert"],
      "speeds": {
        "ATV": {"Highway": 80, "Desert": 72},
        "Car": {"Highway": 180, "Desert": 50}
      },
      "roadFamilies": {
        "Desert": ["Desert", "Dirt"]
      },
      "lengthPriorPx": [
        {"type": "Desert", "px": 870},
        {"type": "Highway", "px": 118}
      ],
      "trackWidthPx": 990,
      "segmentCountMin": 2,
      "segmentCountMax": 3
    }
    """.trimIndent()

    @Test fun parseLoadsSpeedsAndVersion() {
        val p = ParamsLoader.parse(sampleJson)
        assertNotNull(p)
        assertEquals("8.1.test", p!!.version)
        assertEquals(500, p.simulations)
        assertEquals(7L, p.defaultSeed)
        assertEquals(80, p.speedOf("ATV", "Highway"))
        assertEquals(72, p.speedOf("ATV", "Desert"))
        assertEquals(listOf("Desert", "Dirt"), p.familyOf("Desert"))
        assertTrue(p.lengthPriorPx.any { it.first == "Desert" && it.second == 870.0 })
    }

    @Test fun parseBundledShape() {
        // Minimal check that DEFAULT stays consistent with expected Supercar highway
        assertEquals(320, EngineParams.DEFAULT.speedOf("Supercar", "Highway"))
        assertEquals("8.1.0", EngineParams.DEFAULT.version)
    }

    @Test fun roundTripJson() {
        val p = ParamsLoader.parse(sampleJson)!!
        val again = ParamsLoader.parse(ParamsLoader.toJson(p))!!
        assertEquals(p.version, again.version)
        assertEquals(p.speedOf("Car", "Highway"), again.speedOf("Car", "Highway"))
        assertEquals(p.lengthPriorPx.size, again.lengthPriorPx.size)
    }

    @Test fun useActivatesParsed() {
        val p = ParamsLoader.parse(sampleJson)!!
        EngineParams.use(p)
        assertEquals("8.1.test", EngineParams.active.version)
        assertEquals(80, SpeedDatabase.speedOf("ATV", "Highway"))
        EngineParams.resetToDefault()
        assertEquals("8.1.0", EngineParams.active.version)
    }
}
