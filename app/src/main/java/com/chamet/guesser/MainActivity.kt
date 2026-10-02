package com.chamet.guesser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.content.Intent as AppIntent

class MainActivity : AppCompatActivity() {

    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvCaptureStatus: TextView
    private lateinit var btnOverlay: Button
    private lateinit var btnCapture: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val OVERLAY_REQ = 1001
    private val CAPTURE_REQ = 1002

    // MediaProjection permission result launcher
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Store the result in a temporary holder; OverlayService will pick it up
            CaptureHolder.resultCode = result.resultCode
            CaptureHolder.data = result.data
            updateStatus()
            Toast.makeText(this, "Screen capture permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Screen capture denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bump session id once per app launch (used in round logs)
        WonLostLogger.nextSessionId(this)

        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvCaptureStatus = findViewById(R.id.tvCaptureStatus)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnCapture = findViewById(R.id.btnCapture)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        btnOverlay.setOnClickListener { requestOverlayPermission() }
        btnCapture.setOnClickListener { requestCapturePermission() }
        btnStart.setOnClickListener { startOverlayService() }
        btnStop.setOnClickListener { stopOverlayService() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnAnalytics).setOnClickListener {
            startActivity(Intent(this, AnalyticsActivity::class.java))
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    // -------- Overlay permission --------
    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Already granted", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, OVERLAY_REQ)
    }

    // -------- Screen capture permission --------
    private fun requestCapturePermission() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    // -------- Start / stop service --------
    private fun startOverlayService() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant overlay permission first", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, OverlayService::class.java)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Overlay started", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlayService() {
        stopService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "Overlay stopped", Toast.LENGTH_SHORT).show()
    }

    // -------- Update UI status --------
    private fun updateStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        tvOverlayStatus.text = if (overlayOk)
            getString(R.string.status_overlay_on)
        else
            getString(R.string.status_overlay_off)

        val captureOk = CaptureHolder.data != null
        tvCaptureStatus.text = if (captureOk)
            getString(R.string.status_capture_on)
        else
            getString(R.string.status_capture_off)
    }
}

/**
 * Simple singleton to pass MediaProjection result from Activity to Service.
 * Cleared after service consumes it.
 */
object CaptureHolder {
    var resultCode: Int = 0
    var data: Intent? = null

    fun clear() {
        resultCode = 0
        data = null
    }
}
