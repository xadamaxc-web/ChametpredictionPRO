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
    private val roundMachine = RoundStateMachine()
    private val raceLoopController = RaceLoopController()
    private val handler = Handler(Looper.getMainLooper())
    private var raceLoopPosted = false
    private val raceLoop = object : Runnable {
        override fun run() {
            driveStateMachine()
            // Session 5: memory sample ~every 30s
            if (System.currentTimeMillis() % 30_000L < 250L) {
                ResourceProbe.snapshot(this@OverlayService, "tick")
            }
            if (raceLoopPosted) handler.postDelayed(this, 200L)
        }
    }
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
        ParamsLoader.loadIntoEngineLogged(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        resultOverlay = ResultOverlay(this, windowManager)
        startForegroundNotification()
        addFloatingButton()
        handler.post(foregroundChecker)
        raceLoopPosted = true
        handler.post(raceLoop)
        ResourceProbe.start()
        ResourceProbe.snapshot(this, "service_start")
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
        raceLoopPosted = false
        handler.removeCallbacks(raceLoop)
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
            .setContentText("Tap: open betting · again: close · race auto-tracks")
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
                    if (!moved) onUserCaptureTap()
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


    /** Session 3 — floating-button tap advances / starts the state machine. */
    private fun onUserCaptureTap() {
        if (Prefs.capturePaused(this)) {
            roundMachine.onEvent(RoundStateMachine.Event.PAUSE)
            Toast.makeText(this, "Capture paused", Toast.LENGTH_SHORT).show()
            return
        }
        val phase = roundMachine.phase()
        when (phase) {
            RoundStateMachine.Phase.IDLE,
            RoundStateMachine.Phase.SAVED,
            RoundStateMachine.Phase.FINISH -> {
                if (Prefs.roundUnrecorded(this) && resultOverlay.isShowing()) {
                    resultOverlay.saveAutoOrUnknown()
                    roundMachine.onEvent(RoundStateMachine.Event.ROUND_SAVED)
                }
                roundMachine.onEvent(RoundStateMachine.Event.OPEN_BETTING)
                captureAndShow(CaptureMode.FULL)
            }
            RoundStateMachine.Phase.BETTING -> {
                // Second tap while betting = lock / closed
                roundMachine.onEvent(RoundStateMachine.Event.BETTING_CLOSED)
                resultOverlay.syncPhaseFromMachine(roundMachine.snapshot())
                Toast.makeText(this, "Betting closed — waiting for race", Toast.LENGTH_SHORT).show()
            }
            RoundStateMachine.Phase.CLOSED -> {
                roundMachine.onEvent(RoundStateMachine.Event.RACE_STARTED)
                raceLoopController.onRaceStarted()
                captureForAction(RoundStateMachine.CaptureAction.STRIP_ONCE)
            }
            RoundStateMachine.Phase.RACE -> {
                // Manual track sample
                captureForAction(RoundStateMachine.CaptureAction.TRACK_SAMPLE)
            }
        }
    }

    /**
     * Session 3 — periodic tick: auto phase transitions + scheduled captures
     * (strip once, track ~4 fps, finish check).
     */
    private var lastCountdownPollMs = 0L

    private fun driveStateMachine() {
        if (Prefs.capturePaused(this)) {
            if (!roundMachine.isPaused()) roundMachine.onEvent(RoundStateMachine.Event.PAUSE)
            return
        } else if (roundMachine.isPaused()) {
            roundMachine.onEvent(RoundStateMachine.Event.RESUME)
        }

        // Session 2: auto-capture polls countdown ~1/s and advances phases without taps
        if (Prefs.autoCapture(this) && CaptureHolder.data != null) {
            val now = System.currentTimeMillis()
            if (now - lastCountdownPollMs >= 1000L) {
                lastCountdownPollMs = now
                pollCountdownAndAdvance()
            }
        }

        val snap = roundMachine.tick()
        resultOverlay.syncPhaseFromMachine(snap)
        when (snap.action) {
            RoundStateMachine.CaptureAction.FULL_PRE_RACE -> {
                if (Prefs.autoCapture(this) && !resultOverlay.isShowing()) {
                    captureAndShow(CaptureMode.FULL)
                }
            }
            RoundStateMachine.CaptureAction.STRIP_ONCE ->
                captureForAction(RoundStateMachine.CaptureAction.STRIP_ONCE)
            RoundStateMachine.CaptureAction.TRACK_SAMPLE -> {
                val extra = raceLoopController.nextWork(
                    roundMachine.phase() == RoundStateMachine.Phase.RACE
                )
                if (extra == RaceLoopController.Work.STRIP_RECHECK) {
                    captureForAction(RoundStateMachine.CaptureAction.STRIP_ONCE)
                }
                captureForAction(RoundStateMachine.CaptureAction.TRACK_SAMPLE)
            }
            RoundStateMachine.CaptureAction.FINISH_CHECK -> {
                captureForAction(RoundStateMachine.CaptureAction.FINISH_CHECK)
                if (snap.shouldSave) {
                    if (resultOverlay.isShowing()) {
                        resultOverlay.saveAutoOrUnknown()
                    }
                    roundMachine.markSaved()
                }
            }
            else -> { }
        }
    }

    /**
     * Capture a fractional crop where the card countdown lives, OCR it lightly,
     * and feed seconds into the state machine.
     */
    private fun pollCountdownAndAdvance() {
        scope.launch {
            try {
                val bitmap = ScreenCapture.capture(this@OverlayService) ?: return@launch
                val box = CountdownReader.cropBox(bitmap.width, bitmap.height)
                val crop = android.graphics.Bitmap.createBitmap(
                    bitmap, box.left, box.top, box.width, box.height
                )
                // Reuse ML Kit path via OCRHelper raw: readFull is heavy — use countdown text only
                val text = OCRHelper.readTextOnly(crop)
                val sec = CountdownReader.parseSeconds(text)
                if (sec != null) {
                    roundMachine.applyCountdown(sec)
                    // Manual road picker only near end with no road reading
                    if (sec <= 6 && resultOverlay.currentRevealedRoad() == null) {
                        resultOverlay.promptManualRoadIfNeeded()
                    }
                }
                // If idle and auto, open betting when we see a high countdown
                if (sec != null && sec >= 20 &&
                    (roundMachine.phase() == RoundStateMachine.Phase.IDLE ||
                        roundMachine.phase() == RoundStateMachine.Phase.SAVED)
                ) {
                    roundMachine.onEvent(RoundStateMachine.Event.OPEN_BETTING)
                }
            } catch (e: Exception) {
                android.util.Log.w("OverlayService", "countdown poll: ${e.message}")
            }
        }
    }

    private fun captureForAction(action: RoundStateMachine.CaptureAction) {
        if (CaptureHolder.data == null) return
        if (action == RoundStateMachine.CaptureAction.NONE) return
        scope.launch {
            try {
                val bitmap = ScreenCapture.capture(this@OverlayService) ?: return@launch
                when (action) {
                    RoundStateMachine.CaptureAction.STRIP_ONCE -> {
                        val road = resultOverlay.currentRevealedRoad()
                        tryApplyStrip(bitmap, road)
                        roundMachine.markStripCaptured()
                        // Lightweight lane assignment from last known cards
                        resultOverlay.applyLaneCarsFromPositions()
                    }
                    RoundStateMachine.CaptureAction.TRACK_SAMPLE -> {
                        resultOverlay.addTrackSampleFromBitmap(bitmap)
                        roundMachine.markTrackSampled()
                    }
                    RoundStateMachine.CaptureAction.FINISH_CHECK -> {
                        resultOverlay.finaliseTrack()
                        // Session 4: OCR finish screen for winner hint (track still preferred)
                        try {
                            val ocr = OCRHelper.readFull(this@OverlayService, bitmap)
                            val cars = resultOverlay.currentLaneOrPositionCars()
                            val fromText = FinishScreenMatcher.matchFromText(ocr.rawText, cars)
                            if (fromText != null) {
                                resultOverlay.applyFinishScreenWinner(fromText)
                            }
                        } catch (_: Exception) { }
                        roundMachine.onEvent(RoundStateMachine.Event.WINNER_KNOWN)
                        raceLoopController.onRaceEnded()
                    }
                    else -> { }
                }
            } catch (e: Exception) {
                android.util.Log.w("OverlayService", "action $action failed: ${e.message}")
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
            // Session 3: strip visible ⇒ race has started
            if (roundMachine.phase() == RoundStateMachine.Phase.BETTING ||
                roundMachine.phase() == RoundStateMachine.Phase.CLOSED) {
                roundMachine.onEvent(RoundStateMachine.Event.RACE_STARTED)
                raceLoopController.onRaceStarted()
            }
            roundMachine.markStripCaptured()
            if (strip.confidence < 0.55) {
                saveLowConfidenceCrop(bitmap, strip)
                // Session 3: send strip crop only to AI for a second opinion (best-effort)
                scope.launch {
                    try {
                        val y0 = (bitmap.height * 0.75f).toInt()
                        val y1 = (bitmap.height * 0.93f).toInt().coerceAtLeast(y0 + 1)
                        val x0 = strip.startX.coerceIn(0, bitmap.width - 1)
                        val x1 = strip.endX.coerceIn(x0 + 1, bitmap.width)
                        val crop = android.graphics.Bitmap.createBitmap(bitmap, x0, y0, x1 - x0, y1 - y0)
                        AiOcrClient.analyze(this@OverlayService, crop, roadOnly = true)
                        // Result is logged by provider; layout stays local until confidence improves
                    } catch (_: Exception) { }
                }
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
            pruneLowConfCrops(dir, keep = 20)
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

    private fun pruneLowConfCrops(dir: java.io.File, keep: Int) {
        try {
            val files = dir.listFiles { f -> f.name.startsWith("strip_lowconf_") && f.name.endsWith(".png") }
                ?.sortedByDescending { it.lastModified() } ?: return
            files.drop(keep).forEach { it.delete() }
        } catch (_: Exception) { }
    }

}
