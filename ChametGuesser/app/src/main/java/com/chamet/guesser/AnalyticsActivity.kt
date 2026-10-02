package com.chamet.guesser

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AnalyticsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analytics)
        findViewById<Button>(R.id.btnExportAnalytics).setOnClickListener {
            WonLostLogger.exportCsv(this)
        }
        findViewById<Button>(R.id.btnRefreshAnalytics).setOnClickListener { load() }
        load()
    }

    private fun load() {
        val tv = findViewById<TextView>(R.id.tvAnalytics)
        tv.text = "Loading…"
        CoroutineScope(Dispatchers.Main).launch {
            val text = withContext(Dispatchers.IO) { buildReport() }
            tv.text = text
            PositionBiasHelper.refreshFromHistory(this@AnalyticsActivity)
        }
    }

    private suspend fun buildReport(): String {
        val rows = RoundDatabase.get(this).roundDao().getAll()
        return AnalyticsStats.report(rows, WonLostLogger.getBalance(this), Prefs.biasNote(this))
    }
}
