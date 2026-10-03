package com.chamet.guesser

import java.util.UUID

/**
 * Session 4 — data integrity helpers (pure where possible).
 */
object DataIntegrity {

    /** Assign a UUID to every row whose roundUuid is blank. */
    fun backfillRoundUuids(rows: List<RoundEntity>): List<RoundEntity> =
        rows.map { r ->
            if (r.roundUuid.isBlank()) r.copy(roundUuid = UUID.randomUUID().toString())
            else r
        }

    /**
     * Rebuild length prior from logged strip segments only (measured).
     * Returns null if fewer than [minSegments] measurements.
     */
    fun measuredLengthPrior(
        rows: List<RoundEntity>,
        minSegments: Int = 8
    ): List<Pair<String, Double>>? {
        val out = mutableListOf<Pair<String, Double>>()
        for (r in rows) {
            fun add(t: String?, px: Double?) {
                if (!t.isNullOrBlank() && t != "???" && px != null && px > 0) {
                    out += t to px
                }
            }
            add(r.roadType1, r.roadPx1)
            add(r.roadType2, r.roadPx2)
            add(r.roadType3, r.roadPx3)
        }
        return if (out.size >= minSegments) out else null
    }

    /**
     * SQL statements that must appear in MIGRATION_4_5 (unit-tested).
     * A real instrumented Room test still needs an emulator — marked unverified.
     */
    fun migrationCoversRoundUuid(): Boolean {
        val sql = RoundDatabase.MIGRATION_4_5_SQL.joinToString("\n")
        return sql.contains("roundUuid") &&
            sql.contains("CREATE TABLE IF NOT EXISTS race_track")
    }

    fun migrationIsNonDestructive(): Boolean {
        val sql = RoundDatabase.MIGRATION_4_5_SQL.joinToString("\n").uppercase()
        return !sql.contains("DROP TABLE") && !sql.contains("DELETE FROM")
    }
}
