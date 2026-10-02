package com.chamet.guesser

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PositionBiasHelper {
    /**
     * Updates the position multipliers from every round whose winner position is known
     * (a WON tap on any car, including a car we did not bet on). Needs 30+ such rounds.
     * P3 share > 40% -> 0.85 / 1.0 / 1.25; P1 share > 40% -> 1.25 / 1.0 / 0.85; else neutral.
     */
    suspend fun refreshFromHistory(context: Context) = withContext(Dispatchers.IO) {
        if (!Prefs.positionBiasEnabled(context)) return@withContext
        val rows = RoundDatabase.get(context).roundDao().getAll()
        val m = AnalyticsStats.biasMultipliers(rows) ?: return@withContext
        Prefs.setPositionMultipliers(context, m.first, m.second, m.third)
    }
}
