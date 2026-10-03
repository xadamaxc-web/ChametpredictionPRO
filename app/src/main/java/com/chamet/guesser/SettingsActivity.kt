package com.chamet.guesser

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as SysSettings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val rg = findViewById<RadioGroup>(R.id.rgOcrMode)
        val rbMlkit = findViewById<RadioButton>(R.id.rbMlkit)
        val rbHybrid = findViewById<RadioButton>(R.id.rbHybrid)
        val rbAi = findViewById<RadioButton>(R.id.rbAi)

        when (Prefs.ocrMode(this)) {
            "mlkit" -> rbMlkit.isChecked = true
            "ai" -> rbAi.isChecked = true
            else -> rbHybrid.isChecked = true
        }

        fun loadSlot(i: Int, cb: CheckBox, etKey: EditText, etModel: EditText) {
            val s = Prefs.slot(this, i)
            cb.isChecked = s.enabled
            etKey.setText(s.apiKey)
            etModel.setText(s.model)
        }
        loadSlot(1, findViewById(R.id.cbSlot1), findViewById(R.id.etKey1), findViewById(R.id.etModel1))
        loadSlot(2, findViewById(R.id.cbSlot2), findViewById(R.id.etKey2), findViewById(R.id.etModel2))
        loadSlot(3, findViewById(R.id.cbSlot3), findViewById(R.id.etKey3), findViewById(R.id.etModel3))

        val cbAuto = findViewById<CheckBox>(R.id.cbAutoHide)
        val cbBias = findViewById<CheckBox>(R.id.cbPositionBias)
        cbAuto.isChecked = Prefs.autoHideChametOnly(this)
        cbBias.isChecked = Prefs.positionBiasEnabled(this)

        // Session 9 controller
        val cbObserve = findViewById<CheckBox>(R.id.cbObserveOnly)
        val cbPaused = findViewById<CheckBox>(R.id.cbCapturePaused)
        val etMinConf = findViewById<EditText>(R.id.etMinConf)
        val etStakeCap = findViewById<EditText>(R.id.etStakeCap)
        cbObserve.isChecked = Prefs.observeOnly(this)
        cbPaused.isChecked = Prefs.capturePaused(this)
        etMinConf.setText(Prefs.minConfidence(this).toString())
        etStakeCap.setText(Prefs.stakeCapPercent(this).toString())

        findViewById<Button>(R.id.btnUsageAccess).setOnClickListener {
            try {
                startActivity(Intent(SysSettings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Open Settings → Usage access manually", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btnLearn).setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val rows = RoundDatabase.get(this@SettingsActivity).roundDao().getAll()
                // Snapshot current params to disk before applying candidate
                ParamsLoader.saveRollback(this@SettingsActivity, EngineParams.active)
                val result = LocalLearner.tryLearn(rows)
                if (result.applied) {
                    ParamsLoader.saveLearned(this@SettingsActivity, EngineParams.active)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
        findViewById<Button>(R.id.btnRollback).setOnClickListener {
            val rolled = ParamsLoader.loadRollback(this)
            val ok = if (rolled != null) {
                EngineParams.use(rolled)
                ParamsLoader.clearLearned(this)
                LocalLearner.clearPrevious()
                true
            } else {
                LocalLearner.rollback()
            }
            Toast.makeText(
                this,
                if (ok) "Rolled back to ${EngineParams.active.version}" else "Nothing to roll back",
                Toast.LENGTH_SHORT
            ).show()
        }

        val cbAutoCap = findViewById<CheckBox>(R.id.cbAutoCapture)
        cbAutoCap?.isChecked = Prefs.autoCapture(this)
        cbAutoCap?.setOnCheckedChangeListener { _, v -> Prefs.setAutoCapture(this, v) }

        val cbServer = findViewById<CheckBox>(R.id.cbServerEnabled)
        val etUrl = findViewById<EditText>(R.id.etServerUrl)
        cbServer?.isChecked = Prefs.serverEnabled(this)
        etUrl?.setText(Prefs.serverBaseUrl(this))
        findViewById<Button>(R.id.btnServerLogin)?.setOnClickListener {
            val email = findViewById<EditText>(R.id.etServerEmail)?.text?.toString().orEmpty()
            val pass = findViewById<EditText>(R.id.etServerPassword)?.text?.toString().orEmpty()
            Prefs.setServerEnabled(this, true)
            Prefs.setServerBaseUrl(this, etUrl?.text?.toString().orEmpty())
            CoroutineScope(Dispatchers.Main).launch {
                val ok = ServerClient.login(this@SettingsActivity, email, pass)
                Toast.makeText(this@SettingsActivity, if (ok) "Logged in" else "Login failed", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnRequestLease)?.setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                val l = ServerClient.requestLease(this@SettingsActivity)
                Toast.makeText(
                    this@SettingsActivity,
                    if (l?.active == true) "Lease until ${l.expiresAt}" else "Lease failed",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        findViewById<Button>(R.id.btnRedeem)?.setOnClickListener {
            val code = findViewById<EditText>(R.id.etRedeemCode)?.text?.toString().orEmpty()
            CoroutineScope(Dispatchers.Main).launch {
                val ok = ServerClient.redeem(this@SettingsActivity, code)
                Toast.makeText(this@SettingsActivity, if (ok) "Redeemed" else "Redeem failed", Toast.LENGTH_SHORT).show()
            }
        }


        findViewById<Button>(R.id.btnSave).setOnClickListener {
            Prefs.setAutoHideChametOnly(this, cbAuto.isChecked)
            Prefs.setPositionBiasEnabled(this, cbBias.isChecked)
            findViewById<CheckBox>(R.id.cbAutoCapture)?.let {
                Prefs.setAutoCapture(this, it.isChecked)
            }
            Prefs.setObserveOnly(this, cbObserve.isChecked)
            cbServer?.let { Prefs.setServerEnabled(this, it.isChecked) }
            etUrl?.let { Prefs.setServerBaseUrl(this, it.text.toString().trim()) }
            Prefs.setCapturePaused(this, cbPaused.isChecked)
            Prefs.setMinConfidence(this, etMinConf.text.toString().toIntOrNull() ?: 35)
            Prefs.setStakeCapPercent(this, etStakeCap.text.toString().toIntOrNull() ?: 7)

            val mode = when (rg.checkedRadioButtonId) {
                R.id.rbMlkit -> "mlkit"
                R.id.rbAi -> "ai"
                else -> "hybrid"
            }
            Prefs.setOcrMode(this, mode)
            Prefs.setSlot(this, 1,
                findViewById<CheckBox>(R.id.cbSlot1).isChecked,
                findViewById<EditText>(R.id.etKey1).text.toString().trim(),
                findViewById<EditText>(R.id.etModel1).text.toString().trim().ifBlank { Prefs.defaultModel("gemini") },
                "gemini")
            Prefs.setSlot(this, 2,
                findViewById<CheckBox>(R.id.cbSlot2).isChecked,
                findViewById<EditText>(R.id.etKey2).text.toString().trim(),
                findViewById<EditText>(R.id.etModel2).text.toString().trim().ifBlank { Prefs.defaultModel("groq") },
                "groq")
            Prefs.setSlot(this, 3,
                findViewById<CheckBox>(R.id.cbSlot3).isChecked,
                findViewById<EditText>(R.id.etKey3).text.toString().trim(),
                findViewById<EditText>(R.id.etModel3).text.toString().trim().ifBlank { Prefs.defaultModel("openrouter") },
                "openrouter")
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }

        fun testSlot(index: Int) {
            Toast.makeText(this, "Testing slot $index…", Toast.LENGTH_SHORT).show()
            val s = Prefs.slot(this, index)
            if (s.apiKey.isBlank()) {
                Toast.makeText(this, "Enter API key first", Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, "${s.id} key set · model=${s.model}", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnTest1).setOnClickListener { testSlot(1) }
        findViewById<Button>(R.id.btnTest2).setOnClickListener { testSlot(2) }
        findViewById<Button>(R.id.btnTest3).setOnClickListener { testSlot(3) }
    }
}
