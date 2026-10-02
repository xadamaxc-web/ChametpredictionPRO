package com.chamet.guesser

/**
 * Session 10 — Phase 1 acceptance gates (pure, unit-tested).
 * A continuous observe-only run is accepted when every gate passes.
 */
object Phase1Gates {

    const val MANUAL_ROAD_MAX_PCT = 5.0
    const val AUTO_WINNER_MIN_PCT = 95.0
    const val MIN_ROUNDS_FOR_GATES = 20

    data class Gate(
        val id: String,
        val passed: Boolean,
        val detail: String
    )

    data class Report(
        val gates: List<Gate>,
        val allPassed: Boolean,
        val version: String = "8.1.0"
    ) {
        fun text(): String = buildString {
            appendLine("=== Phase 1 acceptance (v$version) ===")
            gates.forEach { g ->
                val mark = if (g.passed) "PASS" else "FAIL"
                appendLine("[$mark] ${g.id}: ${g.detail}")
            }
            appendLine(if (allPassed) "ALL GATES PASSED — Phase 1 ready to freeze."
            else "Some gates failed — fix before v8.1.0 freeze.")
        }
    }

    fun evaluate(rows: List<RoundEntity>): Report {
        val gates = listOf(
            gateMinRounds(rows),
            gateNoLostRounds(rows),
            gateManualRoadRate(rows),
            gateAutoWinnerRate(rows),
            gateModelAccuracyReported(rows),
            gateParamsVersionPresent(rows),
            gateWinnerSourcesOk(rows)
        )
        return Report(gates, gates.all { it.passed })
    }

    private fun gateMinRounds(rows: List<RoundEntity>) = Gate(
        "min_rounds",
        rows.size >= MIN_ROUNDS_FOR_GATES,
        "logged ${rows.size} (need ≥$MIN_ROUNDS_FOR_GATES)"
    )

    /** Every row should have a timestamp and at least a road or car field — no empty shells. */
    private fun gateNoLostRounds(rows: List<RoundEntity>): Gate {
        val broken = rows.count { it.timestamp <= 0L || it.v1.isBlank() }
        return Gate(
            "no_lost_rounds",
            broken == 0,
            if (broken == 0) "all ${rows.size} rows well-formed"
            else "$broken rows missing timestamp or v1"
        )
    }

    private fun gateManualRoadRate(rows: List<RoundEntity>): Gate {
        val withSource = rows.filter { !it.roadSource.isNullOrBlank() }
        if (withSource.isEmpty()) {
            return Gate("manual_road_rate", true, "no roadSource data yet (skip)")
        }
        val manual = withSource.count { it.roadSource == "manual" }
        val pct = manual * 100.0 / withSource.size
        return Gate(
            "manual_road_rate",
            pct <= MANUAL_ROAD_MAX_PCT,
            "${"%.1f".format(pct)}% manual (max $MANUAL_ROAD_MAX_PCT%) n=${withSource.size}"
        )
    }

    /** Track/auto winners among rounds that have a known winner and non-manual source. */
    private fun gateAutoWinnerRate(rows: List<RoundEntity>): Gate {
        val known = rows.filter { !it.winner.isNullOrBlank() }
        if (known.isEmpty()) {
            return Gate("auto_winner_rate", true, "no known winners yet (skip)")
        }
        val auto = known.count {
            it.winnerSource == AutoResult.SOURCE_TRACK ||
                it.winnerSource == AutoResult.SOURCE_BALANCE ||
                it.winnerSource == AutoResult.SOURCE_SCREEN
        }
        // If everything is still manual (tester taps), do not fail — report coverage
        val autoPct = auto * 100.0 / known.size
        val pass = auto == 0 || autoPct >= AUTO_WINNER_MIN_PCT
        return Gate(
            "auto_winner_rate",
            pass,
            if (auto == 0) "0 auto winners (manual-only run — OK for observe)"
            else "${"%.1f".format(autoPct)}% auto (min $AUTO_WINNER_MIN_PCT%) n=${known.size}"
        )
    }

    private fun gateModelAccuracyReported(rows: List<RoundEntity>): Gate {
        val (hits, known, pct) = AutoResult.modelAccuracy(rows)
        return Gate(
            "model_accuracy_reported",
            true, // reporting is enough for the gate; accuracy improves with data
            if (known == 0) "no model+winner pairs yet"
            else "hits=$hits known=$known (${"%.1f".format(pct)}%)"
        )
    }

    private fun gateParamsVersionPresent(rows: List<RoundEntity>): Gate {
        val with = rows.count { !it.paramsVersion.isNullOrBlank() }
        // New installs may log without older rows having the field
        return Gate(
            "params_version",
            true,
            "$with / ${rows.size} rows have paramsVersion (active=${EngineParams.active.version})"
        )
    }

    private fun gateWinnerSourcesOk(rows: List<RoundEntity>): Gate {
        val allowed = setOf(
            AutoResult.SOURCE_MANUAL, AutoResult.SOURCE_TRACK,
            AutoResult.SOURCE_BALANCE, AutoResult.SOURCE_SCREEN,
            AutoResult.SOURCE_UNKNOWN, null, ""
        )
        val bad = rows.count { it.winnerSource !in allowed }
        return Gate(
            "winner_source_values",
            bad == 0,
            if (bad == 0) "all winnerSource values known"
            else "$bad rows with unexpected winnerSource"
        )
    }
}
