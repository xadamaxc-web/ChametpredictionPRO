package com.chamet.guesser

/**
 * Session 10 — Phase 1 acceptance gates (honest / fail-closed).
 * Gates that need data **fail** when the sample is too small, instead of
 * soft-passing. A continuous observe-only run is accepted only when every
 * gate passes with real evidence.
 */
object Phase1Gates {

    const val MANUAL_ROAD_MAX_PCT = 5.0
    const val AUTO_WINNER_MIN_PCT = 95.0
    const val MIN_ROUNDS_FOR_GATES = 20
    const val MIN_ROAD_SOURCE_SAMPLES = 10
    const val MIN_WINNER_SAMPLES = 10
    const val MIN_MODEL_SAMPLES = 10

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
            appendLine(
                if (allPassed) "ALL GATES PASSED — Phase 1 ready to freeze."
                else "Some gates failed — fix before claiming acceptance."
            )
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

    private fun gateNoLostRounds(rows: List<RoundEntity>): Gate {
        if (rows.isEmpty()) {
            return Gate("no_lost_rounds", false, "no rows to inspect")
        }
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
        if (withSource.size < MIN_ROAD_SOURCE_SAMPLES) {
            return Gate(
                "manual_road_rate",
                false,
                "need ≥$MIN_ROAD_SOURCE_SAMPLES roadSource samples (have ${withSource.size})"
            )
        }
        val manual = withSource.count { it.roadSource.equals("manual", true) }
        val pct = manual * 100.0 / withSource.size
        return Gate(
            "manual_road_rate",
            pct <= MANUAL_ROAD_MAX_PCT,
            "${"%.1f".format(pct)}% manual (max $MANUAL_ROAD_MAX_PCT%) n=${withSource.size}"
        )
    }

    /**
     * Among rounds with a known winner, the share whose winnerSource is
     * track / balance / screen must be ≥ AUTO_WINNER_MIN_PCT.
     * Manual-only runs **fail** this gate (they are not auto).
     */
    private fun gateAutoWinnerRate(rows: List<RoundEntity>): Gate {
        val known = rows.filter { !it.winner.isNullOrBlank() && !it.winnerSource.isNullOrBlank() }
        if (known.size < MIN_WINNER_SAMPLES) {
            return Gate(
                "auto_winner_rate",
                false,
                "need ≥$MIN_WINNER_SAMPLES winners with source (have ${known.size})"
            )
        }
        val auto = known.count {
            val s = it.winnerSource
            s == AutoResult.SOURCE_TRACK ||
                s == AutoResult.SOURCE_BALANCE ||
                s == AutoResult.SOURCE_SCREEN
        }
        val autoPct = auto * 100.0 / known.size
        return Gate(
            "auto_winner_rate",
            autoPct >= AUTO_WINNER_MIN_PCT,
            "${"%.1f".format(autoPct)}% auto (min $AUTO_WINNER_MIN_PCT%) n=${known.size}"
        )
    }

    private fun gateModelAccuracyReported(rows: List<RoundEntity>): Gate {
        val (hits, known, pct) = AutoResult.modelAccuracy(rows)
        if (known < MIN_MODEL_SAMPLES) {
            return Gate(
                "model_accuracy_reported",
                false,
                "need ≥$MIN_MODEL_SAMPLES model+winner pairs (have $known)"
            )
        }
        return Gate(
            "model_accuracy_reported",
            true,
            "hits=$hits known=$known (${"%.1f".format(pct)}%)"
        )
    }

    private fun gateParamsVersionPresent(rows: List<RoundEntity>): Gate {
        if (rows.isEmpty()) {
            return Gate("params_version", false, "no rows")
        }
        val with = rows.count { !it.paramsVersion.isNullOrBlank() }
        val pct = with * 100.0 / rows.size
        return Gate(
            "params_version",
            pct >= 80.0,
            "$with / ${rows.size} (${"%.0f".format(pct)}%) have paramsVersion (active=${EngineParams.active.version})"
        )
    }

    private fun gateWinnerSourcesOk(rows: List<RoundEntity>): Gate {
        if (rows.isEmpty()) {
            return Gate("winner_source_values", false, "no rows")
        }
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
