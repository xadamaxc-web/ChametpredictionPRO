package com.chamet.guesser

import android.content.Context
import android.os.Environment
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar
import java.util.UUID

/**
 * CSV column order for round export. Kept in one place so the migration test
 * can assert every new Session-1 field is present.
 */
object CsvColumns {
    val HEADERS: List<String> = listOf(
        "id", "roundUuid", "timestamp", "year", "month", "day", "hour", "minute", "second",
        "dayOfWeek", "timeSinceLastRound", "sessionId",
        "r1", "r2", "r3",
        "revealedSlot", "visibleRoad", "roadSource",
        "roadType1", "roadType2", "roadType3",
        "roadPx1", "roadPx2", "roadPx3", "trackWidthPx",
        "carPosition1", "carPosition2", "carPosition3",
        "laneCars",
        "mathV1", "mathV2", "mathV3",
        "v1", "v2", "v3",
        "confidence",
        "poolA", "poolB", "poolC", "poolTotalShown",
        "oddsA", "oddsB", "oddsC",
        "evA", "evB", "evC",
        "suggestedBet1", "suggestedBet2",
        "suggestedCar1", "suggestedCar2",
        "totalSuggestedBet",
        "balanceBeforeBet", "balanceAfterBet", "balanceAfterPayout",
        "winner", "winnerPosition", "won", "winnerSource",
        "finishOrder", "modelWinner", "modelTimes", "pickJson",
        "paramsVersion", "captureStage", "synced",
        "modeObserved",
        "lastRefreshTimestamp",
        "ocrSource", "aiProviderUsed", "aiFallbackTriggered"
    )

    fun row(r: RoundEntity): List<Any?> = listOf(
        r.id, r.roundUuid, r.timestamp, r.year, r.month, r.day, r.hour, r.minute, r.second,
        r.dayOfWeek, r.timeSinceLastRound, r.sessionId,
        r.r1, r.r2, r.r3,
        r.revealedSlot, r.visibleRoad, r.roadSource,
        r.roadType1, r.roadType2, r.roadType3,
        r.roadPx1, r.roadPx2, r.roadPx3, r.trackWidthPx,
        r.carPosition1, r.carPosition2, r.carPosition3,
        r.laneCars,
        r.mathV1, r.mathV2, r.mathV3,
        r.v1, r.v2, r.v3,
        r.confidence,
        r.poolA, r.poolB, r.poolC, r.poolTotalShown,
        r.oddsA, r.oddsB, r.oddsC,
        r.evA, r.evB, r.evC,
        r.suggestedBet1, r.suggestedBet2,
        r.suggestedCar1, r.suggestedCar2,
        r.totalSuggestedBet,
        r.balanceBeforeBet, r.balanceAfterBet, r.balanceAfterPayout,
        r.winner, r.winnerPosition, r.won, r.winnerSource,
        r.finishOrder, r.modelWinner, r.modelTimes, r.pickJson,
        r.paramsVersion, r.captureStage, r.synced,
        r.modeObserved,
        r.lastRefreshTimestamp,
        r.ocrSource, r.aiProviderUsed, r.aiFallbackTriggered
    )
}

object WonLostLogger {

    var currentRoundId: Long = -1L
    private val scope = CoroutineScope(Dispatchers.IO)

    private const val PREFS = "chamet_guesser_prefs"
    private const val KEY_LAST_TS = "lastRoundTimestamp"
    private const val KEY_SESSION = "sessionId"
    private const val KEY_BALANCE = "diamondBalance"

    fun nextSessionId(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = prefs.getLong(KEY_SESSION, 0L) + 1L
        prefs.edit().putLong(KEY_SESSION, next).apply()
        return next
    }

    fun currentSessionId(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_SESSION, 1L)
    }

    fun getBalance(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_BALANCE, 100_000L)
    }

    fun setBalance(context: Context, balance: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_BALANCE, balance.coerceAtLeast(0L)).apply()
    }

    /**
     * Apply bet immediately (subtract total), then if won add payout using last odds.
     */
    fun applyOutcomeToBalance(
        context: Context,
        totalBet: Int,
        won: Boolean?,
        winOdds: Double
    ): Long {
        var bal = getBalance(context)
        bal -= totalBet
        if (won == true && winOdds > 0) {
            bal += (totalBet * winOdds).toLong()
        }
        // If split bets, caller should pass the actual winning stake * odds;
        // here we approximate with totalBet * odds when WON on primary.
        setBalance(context, bal)
        return bal
    }

    fun logRound(
        context: Context,
        r1: String,
        r2: String?,
        r3: String?,
        v1: String,
        v2: String?,
        v3: String?,
        mathV1: String?,
        mathV2: String?,
        mathV3: String?,
        confidence: Int,
        mode: String? = null,
        winner: String? = null,
        won: Boolean? = null,
        // odds / balance snapshot
        balanceBefore: Long = 0,
        balanceAfterBet: Long = 0,
        balanceAfterPayout: Long = 0,
        pools: List<Long> = emptyList(),
        odds: List<Double> = emptyList(),
        evs: List<Double> = emptyList(),
        suggestedBet1: Int = 0,
        suggestedBet2: Int = 0,
        suggestedCar1: String? = null,
        suggestedCar2: String? = null,
        totalSuggestedBet: Int = 0,
        lastRefreshTs: Long = 0,
        carPosition1: String? = null,
        carPosition2: String? = null,
        carPosition3: String? = null,
        winnerPosition: Int? = null,
        ocrSource: String? = null,
        aiProviderUsed: String? = null,
        aiFallbackTriggered: Boolean = false,
        // Session 1 / 2 fields
        revealedSlot: Int? = null,
        visibleRoad: String? = null,
        roadSource: String? = null,
        // Session 4 — full race-strip layout
        roadType1: String? = null,
        roadType2: String? = null,
        roadType3: String? = null,
        roadPx1: Double? = null,
        roadPx2: Double? = null,
        roadPx3: Double? = null,
        trackWidthPx: Double? = null,
        captureStage: String? = null,
        // Session 5 — top-view tracker
        laneCars: String? = null,
        finishOrder: String? = null,
        trackSamples: List<TopViewTracker.Sample> = emptyList(),
        // Session 6 — auto result / model
        winnerSource: String? = null,
        modelWinner: String? = null,
        modelTimes: String? = null,
        pickJson: String? = null,
        paramsVersion: String? = null
    ) {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = now }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastTs = prefs.getLong(KEY_LAST_TS, 0L)
        val timeSince = if (lastTs > 0) now - lastTs else 0L
        prefs.edit().putLong(KEY_LAST_TS, now).apply()

        val dowRaw = cal.get(Calendar.DAY_OF_WEEK)
        val dayOfWeek = if (dowRaw == Calendar.SUNDAY) 7 else dowRaw - 1

        val entity = RoundEntity(
            timestamp = now,
            year = cal.get(Calendar.YEAR),
            month = cal.get(Calendar.MONTH) + 1,
            day = cal.get(Calendar.DAY_OF_MONTH),
            hour = cal.get(Calendar.HOUR_OF_DAY),
            minute = cal.get(Calendar.MINUTE),
            second = cal.get(Calendar.SECOND),
            dayOfWeek = dayOfWeek,
            timeSinceLastRound = timeSince,
            sessionId = currentSessionId(context),
            r1 = r1,
            r2 = r2,
            r3 = r3,
            v1 = v1,
            v2 = v2,
            v3 = v3,
            mathV1 = mathV1,
            mathV2 = mathV2,
            mathV3 = mathV3,
            confidence = confidence,
            modeObserved = mode,
            winner = winner,
            won = won,
            balanceBeforeBet = balanceBefore,
            balanceAfterBet = balanceAfterBet,
            balanceAfterPayout = balanceAfterPayout,
            poolA = pools.getOrElse(0) { 0L },
            poolB = pools.getOrElse(1) { 0L },
            poolC = pools.getOrElse(2) { 0L },
            oddsA = odds.getOrElse(0) { 0.0 },
            oddsB = odds.getOrElse(1) { 0.0 },
            oddsC = odds.getOrElse(2) { 0.0 },
            evA = evs.getOrElse(0) { 0.0 },
            evB = evs.getOrElse(1) { 0.0 },
            evC = evs.getOrElse(2) { 0.0 },
            suggestedBet1 = suggestedBet1,
            suggestedBet2 = suggestedBet2,
            suggestedCar1 = suggestedCar1,
            suggestedCar2 = suggestedCar2,
            totalSuggestedBet = totalSuggestedBet,
            lastRefreshTimestamp = lastRefreshTs,
            carPosition1 = carPosition1,
            carPosition2 = carPosition2,
            carPosition3 = carPosition3,
            winnerPosition = winnerPosition,
            ocrSource = ocrSource,
            aiProviderUsed = aiProviderUsed,
            aiFallbackTriggered = aiFallbackTriggered,
            roundUuid = UUID.randomUUID().toString(),
            revealedSlot = revealedSlot,
            visibleRoad = visibleRoad,
            roadSource = roadSource,
            roadType1 = roadType1,
            roadType2 = roadType2,
            roadType3 = roadType3,
            roadPx1 = roadPx1,
            roadPx2 = roadPx2,
            roadPx3 = roadPx3,
            trackWidthPx = trackWidthPx,
            captureStage = captureStage,
            laneCars = laneCars,
            finishOrder = finishOrder,
            winnerSource = winnerSource,
            modelWinner = modelWinner,
            modelTimes = modelTimes,
            pickJson = pickJson,
            paramsVersion = paramsVersion ?: SpeedDatabase.paramsVersion,
            synced = false
        )

        val db = RoundDatabase.get(context)
        val dao = db.roundDao()
        val trackDao = db.raceTrackDao()
        scope.launch {
            currentRoundId = dao.insert(entity)
            if (trackSamples.isNotEmpty() && entity.roundUuid.isNotBlank()) {
                trackDao.insertAll(TopViewTracker.toEntities(entity.roundUuid, trackSamples))
            }
            // Phase 2: optional server sync (never blocks offline use)
            if (Prefs.serverEnabled(context) && Prefs.serverToken(context).isNotBlank()) {
                try {
                    val jo = org.json.JSONObject()
                        .put("roundUuid", entity.roundUuid)
                        .put("winner", entity.winner)
                        .put("won", entity.won)
                        .put("r1", entity.r1)
                        .put("r2", entity.r2)
                        .put("r3", entity.r3)
                        .put("v1", entity.v1)
                        .put("v2", entity.v2)
                        .put("v3", entity.v3)
                        .put("modelWinner", entity.modelWinner)
                        .put("modelTimes", entity.modelTimes)
                        .put("pickJson", entity.pickJson)
                        .put("paramsVersion", entity.paramsVersion)
                        .put("winnerSource", entity.winnerSource)
                        .put("roadSource", entity.roadSource)
                        .put("visibleRoad", entity.visibleRoad)
                        .put("roadType1", entity.roadType1)
                        .put("roadType2", entity.roadType2)
                        .put("roadType3", entity.roadType3)
                        .put("roadPx1", entity.roadPx1)
                        .put("roadPx2", entity.roadPx2)
                        .put("roadPx3", entity.roadPx3)
                        .put("laneCars", entity.laneCars)
                        .put("finishOrder", entity.finishOrder)
                        .put("timestamp", entity.timestamp)
                    val arr = org.json.JSONArray().put(jo)
                    val trackArr = org.json.JSONArray()
                    for (s in trackSamples) {
                        trackArr.put(org.json.JSONObject()
                            .put("roundUuid", entity.roundUuid)
                            .put("timeMs", s.timeMs)
                            .put("x1", s.x1).put("x2", s.x2).put("x3", s.x3))
                    }
                    val ok = ServerClient.syncRounds(context, arr, trackArr)
                    if (ok && currentRoundId != null) {
                        try { dao.markSynced(currentRoundId!!) } catch (_: Exception) { }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    fun logRound(context: Context, road: String, cars: List<String>, mode: String?) {
        logRound(
            context = context,
            r1 = road,
            r2 = null,
            r3 = null,
            v1 = cars.getOrElse(0) { "" },
            v2 = cars.getOrNull(1),
            v3 = cars.getOrNull(2),
            mathV1 = cars.getOrNull(0),
            mathV2 = cars.getOrNull(1),
            mathV3 = cars.getOrNull(2),
            confidence = 0,
            mode = mode
        )
    }

    /**
     * Export all rounds to CSV (app Documents + public Downloads when possible).
     */
    fun exportCsv(context: Context) {
        scope.launch {
            try {
                val dao = RoundDatabase.get(context).roundDao()
                val rows = dao.getAll()
                val sb = StringBuilder()

                fun esc(v: Any?): String {
                    if (v == null) return ""
                    val s = v.toString()
                    return if (s.contains(',') || s.contains('"') || s.contains('\n')) {
                        "\"" + s.replace("\"", "\"\"") + "\""
                    } else {
                        s
                    }
                }

                val headers = CsvColumns.HEADERS
                sb.append(headers.joinToString(","))
                sb.append("\n")

                for (r in rows) {
                    sb.append(CsvColumns.row(r).joinToString(",") { esc(it) })
                    sb.append("\n")
                }

                val csvText = sb.toString()
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    ?: context.filesDir
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "chamet_guesser_log.csv")
                file.writeText(csvText)

                try {
                    val publicDl = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    )
                    if (publicDl != null && (publicDl.exists() || publicDl.mkdirs())) {
                        File(publicDl, "chamet_guesser_log.csv").writeText(csvText)
                    }
                } catch (_: Exception) {
                }

                launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Exported to Documents/chamet_guesser_log.csv",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Session 10 — full Phase 1 snapshot: rounds CSV + race_track CSV.
     * Seeds the Phase 2 server; write under Documents with a version stamp.
     */
    fun exportFullSnapshot(context: Context) {
        scope.launch {
            try {
                val db = RoundDatabase.get(context)
                val rounds = db.roundDao().getAll()
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
                    ?: context.filesDir
                if (!dir.exists()) dir.mkdirs()

                // rounds
                fun esc(v: Any?): String {
                    if (v == null) return ""
                    val s = v.toString()
                    return if (s.contains(',') || s.contains('"') || s.contains('\n')) {
                        "\"" + s.replace("\"", "\"\"") + "\""
                    } else s
                }
                val roundsFile = java.io.File(dir, "chamet_snapshot_${stamp}_rounds.csv")
                val sb = StringBuilder()
                sb.append(CsvColumns.HEADERS.joinToString(",")).append('\n')
                for (r in rounds) {
                    sb.append(CsvColumns.row(r).joinToString(",") { esc(it) }).append('\n')
                }
                roundsFile.writeText(sb.toString())

                // race_track for every uuid
                val trackFile = java.io.File(dir, "chamet_snapshot_${stamp}_race_track.csv")
                val tb = StringBuilder()
                tb.append("roundUuid,timeMs,x1,x2,x3\n")
                val trackDao = db.raceTrackDao()
                for (r in rounds) {
                    if (r.roundUuid.isBlank()) continue
                    for (s in trackDao.getForRound(r.roundUuid)) {
                        tb.append(listOf(s.roundUuid, s.timeMs, s.x1, s.x2, s.x3)
                            .joinToString(",") { esc(it) }).append('\n')
                    }
                }
                trackFile.writeText(tb.toString())

                // gates report
                val gates = Phase1Gates.evaluate(rounds)
                val gatesFile = java.io.File(dir, "chamet_snapshot_${stamp}_gates.txt")
                gatesFile.writeText(gates.text())

                launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Snapshot ${stamp}: ${rounds.size} rounds · gates ${if (gates.allPassed) "PASS" else "FAIL"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "Snapshot failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

}

