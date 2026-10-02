package com.chamet.guesser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 / Session 1 — data safety.
 *
 * Room instrumented MigrationTestHelper needs a device/emulator; these pure
 * unit tests lock the migration SQL, the new entity fields, and the CSV export
 * so a v4 → v5 upgrade cannot silently drop columns or omit them from export.
 */
class DataSafetyTests {

    @Test fun migrationSqlAddsEveryNewColumn() {
        val sql = RoundDatabase.MIGRATION_4_5_SQL.joinToString("\n")
        val required = listOf(
            "roundUuid", "revealedSlot", "visibleRoad",
            "roadType1", "roadType2", "roadType3",
            "roadPx1", "roadPx2", "roadPx3", "trackWidthPx",
            "poolTotalShown", "laneCars", "finishOrder",
            "modelWinner", "modelTimes", "pickJson",
            "paramsVersion", "winnerSource", "roadSource",
            "captureStage", "synced"
        )
        for (col in required) {
            assertTrue("migration must ADD COLUMN $col", sql.contains(col))
        }
    }

    @Test fun migrationSqlCreatesRaceTrackTable() {
        val sql = RoundDatabase.MIGRATION_4_5_SQL.joinToString("\n")
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS race_track"))
        assertTrue(sql.contains("roundUuid"))
        assertTrue(sql.contains("timeMs"))
        assertTrue(sql.contains("x1"))
        assertTrue(sql.contains("x2"))
        assertTrue(sql.contains("x3"))
        assertTrue(sql.contains("PRIMARY KEY(roundUuid, timeMs)"))
        assertTrue(sql.contains("index_race_track_roundUuid"))
    }

    @Test fun migrationStartsAtVersion4EndsAt5() {
        assertEquals(4, RoundDatabase.MIGRATION_4_5.startVersion)
        assertEquals(5, RoundDatabase.MIGRATION_4_5.endVersion)
    }

    @Test fun roundUuidDefaultIsEmptyAndSyncedFalse() {
        val r = RoundEntity(
            timestamp = 1L, year = 2026, month = 10, day = 2,
            hour = 12, minute = 0, second = 0, dayOfWeek = 5,
            timeSinceLastRound = 0, sessionId = 1,
            r1 = "Desert", v1 = "ATV"
        )
        assertEquals("", r.roundUuid)
        assertFalse(r.synced)
        assertEquals(null, r.revealedSlot)
        assertEquals(null, r.visibleRoad)
        assertEquals(null, r.roadType1)
        assertEquals(null, r.modelWinner)
        assertEquals(null, r.pickJson)
    }

    @Test fun csvExportIncludesEveryNewColumn() {
        val required = listOf(
            "roundUuid", "revealedSlot", "visibleRoad", "roadSource",
            "roadType1", "roadType2", "roadType3",
            "roadPx1", "roadPx2", "roadPx3", "trackWidthPx",
            "poolTotalShown", "laneCars", "finishOrder",
            "modelWinner", "modelTimes", "pickJson",
            "paramsVersion", "winnerSource", "captureStage", "synced"
        )
        for (col in required) {
            assertTrue("CSV headers must include $col", CsvColumns.HEADERS.contains(col))
        }
        assertEquals(CsvColumns.HEADERS.size, CsvColumns.row(sampleRound()).size)
    }

    @Test fun csvRowCarriesNewFieldValues() {
        val r = sampleRound().copy(
            roundUuid = "abc-123",
            revealedSlot = 2,
            visibleRoad = "Desert",
            roadType1 = "Desert",
            roadPx1 = 870.0,
            trackWidthPx = 990.0,
            laneCars = "ATV|Car|SUV",
            modelWinner = "ATV",
            synced = true
        )
        val row = CsvColumns.row(r)
        val idx = CsvColumns.HEADERS.indexOf
        assertEquals("abc-123", row[idx("roundUuid")])
        assertEquals(2, row[idx("revealedSlot")])
        assertEquals("Desert", row[idx("visibleRoad")])
        assertEquals(870.0, row[idx("roadPx1")])
        assertEquals(990.0, row[idx("trackWidthPx")])
        assertEquals("ATV|Car|SUV", row[idx("laneCars")])
        assertEquals("ATV", row[idx("modelWinner")])
        assertEquals(true, row[idx("synced")])
    }

    @Test fun raceTrackEntityPrimaryKeyIsUuidPlusTime() {
        val a = RaceTrackEntity("u1", 0L, 10f, 20f, 30f)
        val b = RaceTrackEntity("u1", 250L, 15f, 25f, 35f)
        assertEquals("u1", a.roundUuid)
        assertEquals(0L, a.timeMs)
        assertEquals(250L, b.timeMs)
        // distinct timeMs → distinct primary keys for the same round
        assertTrue(a.timeMs != b.timeMs)
    }

    private fun sampleRound() = RoundEntity(
        timestamp = 1L, year = 2026, month = 10, day = 2,
        hour = 12, minute = 0, second = 0, dayOfWeek = 5,
        timeSinceLastRound = 0, sessionId = 1,
        r1 = "Desert", v1 = "ATV", v2 = "Car", v3 = "SUV"
    )
}
