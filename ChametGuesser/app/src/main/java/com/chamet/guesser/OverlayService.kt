package com.chamet.guesser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import android.app.usage.UsageStatsManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingButton: View
    private lateinit var resultOverlay: ResultOverlay
    private val scope = CoroutineScope(Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val foregroundChecker = object : Runnable {
        override fun run() {
            updateOverlayVisibility()
            handler.postDelayed(this, 2000L)
        }
    }

    companion object {
        const val CHANNEL_ID = "chamet_guesser_channel"
        const val NOTIF_ID = 42
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        resultOverlay = ResultOverlay(this, windowManager)
        startForegroundNotification()
        addFloatingButton()
        handler.post(foregroundChecker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            if (::floatingButton.isInitialized) windowManager.removeView(floatingButton)
        } catch (_: Exception) {}
        resultOverlay.removeAll()
        handler.removeCallbacks(foregroundChecker)
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Chamet Guesser", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Chamet Guesser active")
            .setContentText("Tap floating button to capture")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun addFloatingButton() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 30
        params.y = 300

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_button, null)
        view.findViewById<ImageView>(R.id.ivButton)
            .setImageResource(android.R.drawable.ic_menu_camera)

        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f; var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) moved = true
                    params.x = initialX + dx; params.y = initialY + dy
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) captureAndShow(mode = CaptureMode.FULL)
                    true
                }
                else -> false
            }
        }
        windowManager.addView(view, params)
        floatingButton = view
    }

    private enum class CaptureMode { FULL, RECAPTURE, ODDS_ONLY }

    private fun captureAndShow(mode: CaptureMode) {
        if (Prefs.capturePaused(this)) {
            Toast.makeText(this, "Capture paused", Toast.LENGTH_SHORT).show()
            return
        }
        if (mode == CaptureMode.FULL && Prefs.roundUnrecorded(this)) {
            // Session 6: never block the next round — auto-save as winner unknown
            // if the overlay is still up; clear the flag if it is gone.
            if (resultOverlay.isShowing()) {
                resultOverlay.saveAutoOrUnknown()
                Toast.makeText(this, "Last round saved (auto/unknown) · capturing next", Toast.LENGTH_SHORT).show()
            } else {
                Prefs.setRoundUnrecorded(this, false)
            }
        }
        if (CaptureHolder.data == null) {
            Toast.makeText(this, "Grant screen capture permission first", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            try {
                val bitmap = ScreenCapture.capture(this@OverlayService)
                if (bitmap == null) {
                    Toast.makeText(this@OverlayService, "Capture failed", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                resultOverlay.showLoading()
                when (mode) {
                    CaptureMode.ODDS_ONLY -> {
                        val poolsByCar = OCRHelper.readPoolsByCar(this@OverlayService, bitmap)
                        resultOverlay.updatePoolsOnly(poolsByCar)
                    }
                    CaptureMode.RECAPTURE -> {
                        val ocr = OCRHelper.readFull(this@OverlayService, bitmap)
                        resultOverlay.updateCars(
                            ocr.cars, ocr.pools, ocr.positionCars,
                            ocr.source, ocr.aiFallbackTriggered
                        )
                    }
                    CaptureMode.FULL -> {
                        val ocr = OCRHelper.readFull(this@OverlayService, bitmap)
                        if (ocr.balanceHint != null) {
                            WonLostLogger.setBalance(this@OverlayService, ocr.balanceHint)
                        }
                        resultOverlay.showResult(
                            detectedCars = ocr.cars,
                            poolsIn = ocr.pools,
                            positionCarsIn = ocr.positionCars,
                            ocrSource = ocr.source,
                            aiFallback = ocr.aiFallbackTriggered,
                            scope = scope,
                            onRecapture = { captureAndShow(CaptureMode.RECAPTURE) },
                            onRefreshOdds = { captureAndShow(CaptureMode.ODDS_ONLY) }
                        )
                        resultOverlay.applyRevealedRoad(
                            ocr.revealedRoadPosition,
                            ocr.revealedRoadName,
                            ocr.roadSource
                        )
                        // Session 4: try race-strip layout (no-op if checkered lines not visible yet)
                        tryApplyStrip(bitmap, ocr.revealedRoadName)
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@OverlayService, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Session 4 — read road types + lengths from the race strip once.
     * Low-confidence crops are saved under Documents for later training.
     */
    private fun tryApplyStrip(bitmap: android.graphics.Bitmap, visibleRoad: String?) {
        try {
            val strip = RaceStripReader.analyse(bitmap, visibleRoad = visibleRoad)
            if (strip == null || strip.segments.isEmpty()) return
            resultOverlay.applyStripLayout(strip)
            if (strip.confidence < 0.55) {
                saveLowConfidenceCrop(bitmap, strip)
            }
        } catch (e: Exception) {
            android.util.Log.w("OverlayService", "strip read failed: ${e.message}")
        }
    }

    private fun saveLowConfidenceCrop(bitmap: android.graphics.Bitmap, strip: RaceStripReader.StripResult) {
        try {
            val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
                ?: filesDir
            if (!dir.exists()) dir.mkdirs()
            val y0 = (bitmap.height * 0.75f).toInt()
            val y1 = (bitmap.height * 0.93f).toInt().coerceAtLeast(y0 + 1)
            val x0 = strip.startX.coerceIn(0, bitmap.width - 1)
            val x1 = strip.endX.coerceIn(x0 + 1, bitmap.width)
            val crop = android.graphics.Bitmap.createBitmap(bitmap, x0, y0, x1 - x0, y1 - y0)
            val file = java.io.File(dir, "strip_lowconf_${System.currentTimeMillis()}.png")
            java.io.FileOutputStream(file).use { out ->
                crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
            }
            crop.recycle()
        } catch (_: Exception) {
        }
    }

    private fun updateOverlayVisibility() {
        if (!::floatingButton.isInitialized) return
        if (!Prefs.autoHideChametOnly(this)) {
            floatingButton.visibility = View.VISIBLE
            return
        }
        val pkg = foregroundPackage()
        val show = pkg == null || pkg.startsWith("com.chamet")
        floatingButton.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun foregroundPackage(): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - 5000
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            stats?.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (_: Exception) {
            null // permission missing → treat as always show (caller checks null)
        }
    }
}
