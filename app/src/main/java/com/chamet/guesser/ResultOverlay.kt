package com.chamet.guesser

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope

class ResultOverlay(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private var view: View? = null
    private var loadingView: View? = null

    private var r1 = "???"
    private var r2 = "???"
    private var r3 = "???"
    private var lockedSlot = 0
    private var postRace = false

    // vehicles[0]=1st, [1]=2nd, [2]=3rd
    private var vehicles: MutableList<String> = mutableListOf("—", "—", "—")
    private var mathRank: List<String> = emptyList()
    private var poolByCar: Map<String, Long> = emptyMap()   // matched by screen position
    private var rankingActive = false                        // true once cars are math-ranked / manually ordered
    private var split: OddsEngine.SplitAdvice? = null
    private var confidence = 0
    private var confidenceLabel = ""
    private var wonFlag: Boolean? = null
    private var winnerCar: String? = null          // the car that really won (only set on WON)
    private var awaitingWinner = false             // WON tapped with two bets: waiting for a car tap
    private var roundStartBalance: Long = 100_000L // balance before this round's bet; settlement is computed from it
    private var lastRefreshTs = 0L
    private var balance: Long = 100_000L
    private var positionCars: List<String> = emptyList()
    private var lastOcrSource: String? = null
    private var lastAiFallback = false
    /** screen | ai | manual | null (unknown) — Session 2 */
    private var roadSource: String? = null

    // Session 4 — full race-strip layout (types + lengths once the race starts)
    private var stripTypes: List<String> = emptyList()
    private var stripPx: List<Double> = emptyList()
    private var stripTrackWidth: Double? = null
    private var stripConfidence: Double = 0.0
    private var captureStage: String? = null

    // Session 5 — top-view tracker
    private var laneCars: List<String> = listOf("—", "—", "—")
    private var trackSamples: List<TopViewTracker.Sample> = emptyList()
    private var finishOrder: List<String> = emptyList()
    private var speedDriftFlag: String? = null
    private var modelWinner: String? = null
    private var modelTimes: String? = null
    private var winnerSource: String? = null

    private var raceLocked = false                 // race started: only WON / LOST / NO BET are active
    private var resultRecorded = false
    private var noBet = false

    private var orderingMode = false
    private val orderTaps = mutableListOf<Int>()

    private var onRecapture: (() -> Unit)? = null
    private var onRefreshOdds: (() -> Unit)? = null

    private var btnR1: Button? = null
    private var btnR2: Button? = null
    private var btnR3: Button? = null
    private var btnV1: Button? = null
    private var btnV2: Button? = null
    private var btnV3: Button? = null
    private var tvOdds1: TextView? = null
    private var tvOdds2: TextView? = null
    private var tvOdds3: TextView? = null
    private var tvBet1: TextView? = null
    private var tvBet2: TextView? = null
    private var tvBet3: TextView? = null
    private var ivCar1: ImageView? = null
    private var ivCar2: ImageView? = null
    private var ivCar3: ImageView? = null
    private var slotV1: LinearLayout? = null
    private var slotV2: LinearLayout? = null
    private var slotV3: LinearLayout? = null
    private var btnWon: Button? = null
    private var btnLost: Button? = null
    private var btnOrder: Button? = null
    private var btnRefreshOdds: Button? = null
    private var btnExport: Button? = null
    private var btnNoBet: Button? = null
    private var btnLock: Button? = null
    private var tvUnrecorded: TextView? = null
    private var btnBalance: TextView? = null
    private var tvConf: TextView? = null
    private var tvPrompt: TextView? = null
    private var tvState: TextView? = null
    private var tvGlance: TextView? = null
    private var dropdownArea: LinearLayout? = null
    private var dropdownScroll: View? = null

    fun showLoading() {
        removeLoadingOnly()
        val params = baseParams()
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = 40
        val tv = TextView(context).apply {
            text = "Reading…"
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundColor(Color.parseColor("#131B2E"))
            setPadding(36, 18, 36, 18)
        }
        windowManager.addView(tv, params)
        loadingView = tv
    }

    fun showResult(
        detectedCars: List<String>,
        poolsIn: List<Long> = emptyList(),
        positionCarsIn: List<String> = emptyList(),
        ocrSource: String? = null,
        aiFallback: Boolean = false,
        scope: CoroutineScope,
        onRecapture: (() -> Unit)? = null,
        onRefreshOdds: (() -> Unit)? = null,
        onLogged: ((String, List<String>, String?) -> Unit)? = null
    ) {
        removeLoadingOnly()
        this.onRecapture = onRecapture
        this.onRefreshOdds = onRefreshOdds
        balance = WonLostLogger.getBalance(context)

        val ranked = detectedCars.take(3)
        vehicles = mutableListOf(
            ranked.getOrElse(0) { "—" },
            ranked.getOrElse(1) { "—" },
            ranked.getOrElse(2) { "—" }
        )
        mathRank = vehicles.toList()
        poolByCar = PositionMapper.poolsByCar(positionCarsIn, poolsIn)
        positionCars = positionCarsIn
        laneCars = TopViewTracker.assignLanes(positionCarsIn)
        rankingActive = false
        lastOcrSource = ocrSource
        lastAiFallback = aiFallback
        roadSource = null
        stripTypes = emptyList(); stripPx = emptyList()
        stripTrackWidth = null; stripConfidence = 0.0; captureStage = null
        laneCars = listOf("—", "—", "—"); trackSamples = emptyList()
        finishOrder = emptyList(); speedDriftFlag = null
        modelWinner = null; modelTimes = null; winnerSource = null
        confidence = 0
        confidenceLabel = ""
        wonFlag = null
        winnerCar = null
        awaitingWinner = false
        raceLocked = false; resultRecorded = false; noBet = false
        roundStartBalance = balance
        split = null
        lastRefreshTs = System.currentTimeMillis()

        if (view == null) {
            r1 = "???"; r2 = "???"; r3 = "???"
            lockedSlot = 0; postRace = false
            inflateOverlay()
        }
        recomputeOdds()
        refreshAll()
    }

    fun updateCars(detectedCars: List<String>, poolsIn: List<Long> = emptyList(),
        positionCarsIn: List<String> = emptyList(), ocrSource: String? = null, aiFallback: Boolean = false) {
        removeLoadingOnly()
        val ranked = detectedCars.take(3)
        vehicles = mutableListOf(
            ranked.getOrElse(0) { "—" },
            ranked.getOrElse(1) { "—" },
            ranked.getOrElse(2) { "—" }
        )
        mathRank = vehicles.toList()
        poolByCar = PositionMapper.poolsByCar(positionCarsIn, poolsIn)
        positionCars = positionCarsIn
        laneCars = TopViewTracker.assignLanes(positionCarsIn)
        rankingActive = false
        lastOcrSource = ocrSource
        lastAiFallback = aiFallback
        roadSource = null
        stripTypes = emptyList(); stripPx = emptyList()
        stripTrackWidth = null; stripConfidence = 0.0; captureStage = null
        laneCars = listOf("—", "—", "—"); trackSamples = emptyList()
        finishOrder = emptyList(); speedDriftFlag = null
        modelWinner = null; modelTimes = null; winnerSource = null
        confidence = 0
        confidenceLabel = ""
        wonFlag = null
        winnerCar = null
        awaitingWinner = false
        raceLocked = false; resultRecorded = false; noBet = false
        roundStartBalance = balance
        split = null
        lockedSlot = 0
        postRace = false
        r1 = "???"; r2 = "???"; r3 = "???"
        lastRefreshTs = System.currentTimeMillis()
        if (view == null) inflateOverlay()
        recomputeOdds()
        refreshAll()
    }

    /** 🔄 Refresh odds only — keep roads/cars/mode/balance */
    fun updatePoolsOnly(poolsByCar: Map<String, Long>) {
        removeLoadingOnly()
        poolByCar = poolsByCar
        lastRefreshTs = System.currentTimeMillis()
        recomputeOdds()
        refreshAll()
        Toast.makeText(context, "Odds refreshed", Toast.LENGTH_SHORT).show()
    }

    private fun recomputeOdds() {
        if (!ServerClient.engineUnlocked(context)) {
            // Phase 2 lock: no stake advice without lease when server mode on
            return
        }

        val cars = vehicles.filter { it != "—" }
        if (cars.isEmpty()) {
            split = null
            return
        }
        var winProb: Map<String, Double> = emptyMap()
        // If a road is set, use Guesser confidence + score-based win chances
        val road = revealedRoad()
        if (road != "???" && RoadMatcher.isKnown(road)) {
            val biasMap = buildPositionBiasMap()
            val known = stripTypes.zip(stripPx).filter { it.second > 0 }
                .takeIf { it.isNotEmpty() }
            val result = Guesser.guess(
                revealedRoad = road,
                offeredCars = cars,
                positionBias = biasMap,
                poolByCar = poolByCar,
                knownSegments = known
            )
            if (result != null) {
                vehicles = result.ranked.map { it.car }.toMutableList()
                while (vehicles.size < 3) vehicles.add("—")
                // keep mathRank only if first rank
                if (mathRank.isEmpty() || mathRank.all { it == "—" }) {
                    mathRank = vehicles.toList()
                }
                confidence = result.confidence
                confidenceLabel = result.confidenceLabel
                rankingActive = true
                winProb = result.ranked.associate { it.car to it.winProb }
                if (result.layoutKnown) {
                    modelWinner = result.ranked.firstOrNull()?.car
                    modelTimes = result.ranked.joinToString(",") {
                        "${it.car}:${"%.3f".format(it.meanTime)}"
                    }
                }
            }
        } else {
            // Road unknown: still show pools/odds but mark and lower confidence (Session 2)
            confidence = 0
            confidenceLabel = "ROAD UNKNOWN"
        }
        val orderedCars = vehicles.filter { it != "—" }
        val rawAdvice = OddsEngine.compute(
            orderedCars, poolByCar, confidence, roundStartBalance, winProb,
            stakeCapPercent = Prefs.stakeCapPercent(context)
        )
        split = OverlayController.filterAdvice(
            rawAdvice,
            confidence,
            Prefs.minConfidence(context),
            Prefs.observeOnly(context)
        )
    }

    private fun inflateOverlay() {
        val root = LayoutInflater.from(context).inflate(R.layout.overlay_result, null)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenH = metrics.heightPixels
        val overlayH = (screenH * 0.45).toInt()
        val topPad = (screenH * 0.05).toInt()

        val params = baseParams()
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = overlayH
        params.gravity = Gravity.TOP
        params.y = topPad
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        btnR1 = root.findViewById(R.id.btnR1)
        btnR2 = root.findViewById(R.id.btnR2)
        btnR3 = root.findViewById(R.id.btnR3)
        btnV1 = root.findViewById(R.id.btnV1)
        btnV2 = root.findViewById(R.id.btnV2)
        btnV3 = root.findViewById(R.id.btnV3)
        tvOdds1 = root.findViewById(R.id.tvOdds1)
        tvOdds2 = root.findViewById(R.id.tvOdds2)
        tvOdds3 = root.findViewById(R.id.tvOdds3)
        tvBet1 = root.findViewById(R.id.tvBet1)
        tvBet2 = root.findViewById(R.id.tvBet2)
        tvBet3 = root.findViewById(R.id.tvBet3)
        ivCar1 = root.findViewById(R.id.ivCar1)
        ivCar2 = root.findViewById(R.id.ivCar2)
        ivCar3 = root.findViewById(R.id.ivCar3)
        slotV1 = root.findViewById(R.id.slotV1)
        slotV2 = root.findViewById(R.id.slotV2)
        slotV3 = root.findViewById(R.id.slotV3)
        btnWon = root.findViewById(R.id.btnWon)
        btnLost = root.findViewById(R.id.btnLost)
        btnOrder = root.findViewById(R.id.btnOrder)
        btnRefreshOdds = root.findViewById(R.id.btnRefreshOdds)
        btnExport = root.findViewById(R.id.btnExport)
        btnNoBet = root.findViewById(R.id.btnNoBet)
        btnLock = root.findViewById(R.id.btnLock)
        tvUnrecorded = root.findViewById(R.id.tvUnrecorded)
        btnBalance = root.findViewById(R.id.btnBalance)
        tvConf = root.findViewById(R.id.tvConfidence)
        tvPrompt = root.findViewById(R.id.tvPrompt)
        tvState = root.findViewById(R.id.tvState)
        tvGlance = root.findViewById(R.id.tvGlance)
        dropdownArea = root.findViewById(R.id.dropdownArea)
        dropdownScroll = root.findViewById(R.id.dropdownScroll)

        listOf(btnR1, btnR2, btnR3, btnV1, btnV2, btnV3,
            btnWon, btnLost, btnNoBet, btnLock, btnOrder, btnRefreshOdds, btnExport).forEach {
            it?.let { b -> attachPressScale(b) }
        }

        btnR1?.setOnClickListener { openRoadDropdown(1) }
        btnR2?.setOnClickListener { openRoadDropdown(2) }
        btnR3?.setOnClickListener { openRoadDropdown(3) }
        btnV1?.setOnClickListener { onVehicleTap(0) }
        btnV2?.setOnClickListener { onVehicleTap(1) }
        btnV3?.setOnClickListener { onVehicleTap(2) }
        btnV1?.setOnLongClickListener { openCarChooser(0); true }
        btnV2?.setOnLongClickListener { openCarChooser(1); true }
        btnV3?.setOnLongClickListener { openCarChooser(2); true }
        btnOrder?.setOnClickListener { toggleOrdering() }
        btnExport?.setOnClickListener { WonLostLogger.exportCsv(context) }
        btnExport?.setOnLongClickListener {
            WonLostLogger.exportFullSnapshot(context)
            true
        }
        btnRefreshOdds?.setOnClickListener { onRefreshOdds?.invoke() }
        btnBalance?.setOnClickListener { editBalance() }

        btnWon?.setOnClickListener { onWonTapped() }
        btnLost?.setOnClickListener { if (raceLocked) settle(winner = null, won = false) }
        btnNoBet?.setOnClickListener { if (raceLocked) settleNoBet() }
        btnLock?.setOnClickListener { lockRound() }
        listOf(ivCar1, ivCar2, ivCar3).forEachIndexed { i, iv -> iv?.setOnClickListener { onVehicleTap(i) } }
        root.findViewById<TextView>(R.id.btnCloseX).setOnClickListener {
            if (Prefs.roundUnrecorded(context)) {
                Toast.makeText(context, "Tap WON / LOST / NO BET first", Toast.LENGTH_SHORT).show()
            } else removeAll()
        }

        windowManager.addView(root, params)
        view = root
    }

    /** WON: arm it, then the tap on the winning car's image records result + rank in one tap. */
    private fun onWonTapped() {
        if (!raceLocked) return
        awaitingWinner = true
        tvPrompt?.text = "Tap the winning car"
        tvPrompt?.visibility = View.VISIBLE
    }

    /** Race started: freeze the round. Only WON / LOST / NO BET stay active until one is tapped. */
    private fun lockRound() {
        if (raceLocked) return
        if (revealedRoad() == "???" || vehicles.all { it == "—" }) {
            Toast.makeText(context, "Set the road and cars first", Toast.LENGTH_SHORT).show()
            return
        }
        if (orderingMode) exitOrdering(false)
        raceLocked = true
        resultRecorded = false
        noBet = false
        wonFlag = null
        winnerCar = null
        awaitingWinner = false
        dropdownScroll?.visibility = View.GONE
        Prefs.setRoundUnrecorded(context, true)
        refreshAll()
    }

    private fun settleNoBet() {
        awaitingWinner = false
        tvPrompt?.visibility = View.GONE
        noBet = true
        wonFlag = null
        winnerCar = null
        winnerSource = AutoResult.SOURCE_MANUAL
        resultRecorded = true
        Prefs.setRoundUnrecorded(context, false)
        balance = roundStartBalance
        WonLostLogger.setBalance(context, balance)
        Toast.makeText(context, "NO BET · balance unchanged", Toast.LENGTH_SHORT).show()
        advance()
    }

    fun isShowing(): Boolean = view != null

    fun flashUnrecorded() {
        tvUnrecorded?.visibility = View.VISIBLE
        tvUnrecorded?.animate()?.alpha(0.3f)?.setDuration(150)?.withEndAction {
            tvUnrecorded?.animate()?.alpha(1f)?.setDuration(150)?.start()
        }?.start()
    }

    /**
     * Settle the round from roundStartBalance, so tapping WON then LOST (or twice) never
     * applies the bet twice. Only the bet on the car that really won pays.
     */
    private fun settle(winner: String?, won: Boolean) {
        winnerSource = AutoResult.SOURCE_MANUAL
        awaitingWinner = false
        tvPrompt?.visibility = View.GONE
        wonFlag = won
        winnerCar = winner
        noBet = false
        resultRecorded = true
        Prefs.setRoundUnrecorded(context, false)
        balance = (roundStartBalance + OddsEngine.net(split, winner)).coerceAtLeast(0)
        WonLostLogger.setBalance(context, balance)
        val rank = winner?.let { vehicles.indexOf(it) }?.takeIf { it >= 0 }?.plus(1)
        val msg = when {
            winner == null -> "LOST · balance updated"
            won -> "WON · rank ${rank ?: "?"} · balance updated"
            else -> "Winner rank ${rank ?: "?"} · not our pick"
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        advance()
    }

    private fun editBalance() {
        val input = EditText(context).apply {
            setText(balance.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#1A2340"))
            setPadding(24, 16, 24, 16)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Diamond balance")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val v = input.text.toString().replace(",", "").toLongOrNull()
                if (v != null) {
                    balance = v
                    if (wonFlag == null) roundStartBalance = v
                    WonLostLogger.setBalance(context, balance)
                    recomputeOdds()
                    refreshAll()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            @Suppress("DEPRECATION")
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
        }
        dialog.show()
    }

    private fun attachPressScale(v: View) {
        v.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }
            false
        }
    }

    private fun openRoadDropdown(slot: Int) {
        if (raceLocked) {
            Toast.makeText(context, "Round locked", Toast.LENGTH_SHORT).show()
            return
        }
        if (!postRace && lockedSlot != 0 && lockedSlot != slot) {
            Toast.makeText(context, "Only one road before race (R$lockedSlot)", Toast.LENGTH_SHORT).show()
            return
        }
        val area = dropdownArea ?: return
        area.removeAllViews()
        dropdownScroll?.visibility = View.VISIBLE
        val d = context.resources.displayMetrics.density
        SpeedDatabase.ROADS.forEach { road ->
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { onRoadPicked(slot, road) }
            }
            tile.addView(ImageView(context).apply {
                Assets.ROAD_RES[road]?.let { setImageResource(it) }
                scaleType = ImageView.ScaleType.FIT_XY
            }, LinearLayout.LayoutParams((110 * d).toInt(), (22 * d).toInt()))
            tile.addView(TextView(context).apply {
                text = road; textSize = 9f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            })
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (6 * d).toInt()
            area.addView(tile, lp)
        }
    }

    /** Long-press a car card: pick the right car from the 9 vehicles when OCR got it wrong. */
    private fun openCarChooser(index: Int) {
        if (raceLocked || postRace || orderingMode || awaitingWinner) return
        val area = dropdownArea ?: return
        area.removeAllViews()
        dropdownScroll?.visibility = View.VISIBLE
        val d = context.resources.displayMetrics.density
        CarMatcher.KNOWN_CARS.forEach { car ->
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { onCarChosen(index, car) }
            }
            tile.addView(ImageView(context).apply {
                Assets.CAR_RES[car]?.let { setImageResource(it) }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams((64 * d).toInt(), (44 * d).toInt()))
            tile.addView(TextView(context).apply {
                text = car; textSize = 9f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            })
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (6 * d).toInt()
            area.addView(tile, lp)
        }
    }

    private fun onCarChosen(index: Int, car: String) {
        dropdownScroll?.visibility = View.GONE
        val old = vehicles.getOrElse(index) { "—" }
        if (car == old) return
        if (vehicles.contains(car)) {
            Toast.makeText(context, "$car is already in the list", Toast.LENGTH_SHORT).show()
            return
        }
        vehicles[index] = car
        if (old != "—") {
            poolByCar[old]?.let { poolByCar = poolByCar - old + (car to it) }
            positionCars = positionCars.map { if (it == old) car else it }
            mathRank = mathRank.map { if (it == old) car else it }
        } else if (!rankingActive) {
            val pc = (0..2).map { positionCars.getOrElse(it) { "—" } }.toMutableList()
            pc[index] = car
            positionCars = pc
        }
        recomputeOdds()
        refreshAll()
    }

    private fun onRoadPicked(slot: Int, road: String) {
        when (slot) {
            1 -> r1 = road
            2 -> r2 = road
            3 -> r3 = road
        }
        if (!postRace) lockedSlot = slot
        // Manual pick is the last-resort fallback (Session 2)
        roadSource = "manual"
        dropdownScroll?.visibility = View.GONE
        recomputeOdds()
        refreshAll()
    }

    private fun toggleOrdering() {
        if (raceLocked) return
        if (orderingMode) exitOrdering(false)
        else {
            orderingMode = true
            orderTaps.clear()
            btnOrder?.setBackgroundColor(Color.parseColor("#FCA311"))
            tvPrompt?.text = "Tap order: 1st → 2nd → 3rd"
            tvPrompt?.visibility = View.VISIBLE
        }
    }

    private fun onVehicleTap(index: Int) {
        if (awaitingWinner) {
            val car = vehicles.getOrNull(index)?.takeIf { it != "—" } ?: return
            val s = split
            // our bet won, or no bet was suggested (nothing to pay, trust the WON tap)
            val won = OddsEngine.isBacked(s, car) || (s?.totalBet ?: 0) == 0
            settle(winner = car, won = won)
            return
        }
        if (!orderingMode) return
        if (orderTaps.contains(index)) return
        orderTaps.add(index)
        val label = when (orderTaps.size) { 1 -> "1st"; 2 -> "2nd"; else -> "3rd" }
        val btn = when (index) { 0 -> btnV1; 1 -> btnV2; else -> btnV3 }
        btn?.text = "$label ${vehicles[index]}"
        if (orderTaps.size == 3) exitOrdering(true)
    }

    private fun exitOrdering(apply: Boolean) {
        orderingMode = false
        btnOrder?.setBackgroundResource(R.drawable.btn_neutral)
        tvPrompt?.visibility = View.GONE
        if (apply && orderTaps.size == 3) {
            vehicles = orderTaps.map { vehicles[it] }.toMutableList()
            rankingActive = true
            recomputeOdds()
        }
        orderTaps.clear()
        refreshAll()
    }

    private fun revealedRoad(): String = when (lockedSlot) {
        1 -> r1; 2 -> r2; 3 -> r3
        else -> if (r1 != "???") r1 else if (r2 != "???") r2 else r3
    }

    /**
     * Called right after a result (WON / LOST / NO BET) is recorded: log the round, reset the
     * overlay and capture the next game. There is no Next button any more.
     */
    private fun advance() {
        if (!resultRecorded) return          // safety: never log a round without a result
        val s = split
        val before = roundStartBalance
        // NO BET: nothing staked. Otherwise the whole suggested stake was spent before payout.
        val afterBet = if (noBet) before else before - (s?.totalBet ?: 0)
        val afterPayout = balance

        WonLostLogger.logRound(
            context = context,
            r1 = r1,
            r2 = if (r2 == "???") null else r2,
            r3 = if (r3 == "???") null else r3,
            v1 = vehicles.getOrElse(0) { "" },
            v2 = vehicles.getOrNull(1)?.takeIf { it != "—" },
            v3 = vehicles.getOrNull(2)?.takeIf { it != "—" },
            mathV1 = mathRank.getOrNull(0),
            mathV2 = mathRank.getOrNull(1),
            mathV3 = mathRank.getOrNull(2),
            confidence = confidence,
            mode = null,
            winner = winnerCar,
            won = wonFlag,
            balanceBefore = before,
            balanceAfterBet = afterBet,
            balanceAfterPayout = afterPayout,
            pools = positionCars.map { poolByCar[it] ?: 0L },   // screen order, matches carPosition1..3
            odds = s?.cars?.map { it.odds } ?: emptyList(),
            evs = s?.cars?.map { it.ev } ?: emptyList(),
            suggestedBet1 = s?.bet1 ?: 0,
            suggestedBet2 = s?.bet2 ?: 0,
            suggestedCar1 = s?.car1,
            suggestedCar2 = s?.car2,
            totalSuggestedBet = s?.totalBet ?: 0,
            lastRefreshTs = lastRefreshTs,
            carPosition1 = positionCars.getOrNull(0),
            carPosition2 = positionCars.getOrNull(1),
            carPosition3 = positionCars.getOrNull(2),
            winnerPosition = winnerCar?.let { w -> positionCars.indexOf(w).takeIf { it >= 0 }?.plus(1) },
            ocrSource = lastOcrSource,
            aiProviderUsed = lastOcrSource,
            aiFallbackTriggered = lastAiFallback,
            revealedSlot = lockedSlot.takeIf { it in 1..3 },
            visibleRoad = revealedRoad().takeIf { it != "???" && RoadMatcher.isKnown(it) },
            roadSource = roadSource,
            roadType1 = stripTypes.getOrNull(0),
            roadType2 = stripTypes.getOrNull(1),
            roadType3 = stripTypes.getOrNull(2),
            roadPx1 = stripPx.getOrNull(0),
            roadPx2 = stripPx.getOrNull(1),
            roadPx3 = stripPx.getOrNull(2),
            trackWidthPx = stripTrackWidth,
            captureStage = captureStage,
            laneCars = TopViewTracker.encodeLaneCars(laneCars),
            finishOrder = if (finishOrder.isNotEmpty()) TopViewTracker.encodeFinishOrder(finishOrder) else null,
            trackSamples = trackSamples,
            winnerSource = winnerSource,
            modelWinner = modelWinner,
            modelTimes = modelTimes,
            paramsVersion = SpeedDatabase.paramsVersion
        )
        Prefs.recordRoadSource(context, roadSource)

        // Reset for the next game, then capture it
        r1 = "???"; r2 = "???"; r3 = "???"
        lockedSlot = 0; postRace = false
        stripTypes = emptyList(); stripPx = emptyList()
        stripTrackWidth = null; stripConfidence = 0.0
        captureStage = null; roadSource = null
        laneCars = listOf("—", "—", "—"); trackSamples = emptyList()
        finishOrder = emptyList(); speedDriftFlag = null
        modelWinner = null; modelTimes = null; winnerSource = null
        vehicles = mutableListOf("—", "—", "—")
        mathRank = emptyList()
        poolByCar = emptyMap()
        rankingActive = false
        split = null
        confidence = 0
        wonFlag = null
        winnerCar = null
        awaitingWinner = false
        raceLocked = false; resultRecorded = false; noBet = false
        roundStartBalance = balance
        orderingMode = false
        orderTaps.clear()
        refreshAll()
        // Session 2: do not blind re-capture — next round starts from the state machine
    }

    private fun buildPositionBiasMap(): Map<String, Double> {
        if (!Prefs.positionBiasEnabled(context)) return emptyMap()
        val (m1, m2, m3) = Prefs.getPositionMultipliers(context)
        if (m1 == 1.0 && m2 == 1.0 && m3 == 1.0) return emptyMap()
        val map = mutableMapOf<String, Double>()
        // positionCars[0]=left(P1), [1]=mid(P2), [2]=right(P3)
        positionCars.getOrNull(0)?.takeIf { it != "—" }?.let { map[it] = m1 }
        positionCars.getOrNull(1)?.takeIf { it != "—" }?.let { map[it] = m2 }
        positionCars.getOrNull(2)?.takeIf { it != "—" }?.let { map[it] = m3 }
        return map
    }

    /**
     * Auto-fill R1/R2/R3 from OCR / AI banner read.
     * @param source "screen" | "ai" | null
     * If the road is still unknown after this call, opens the manual picker (last fallback).
     */
    fun applyRevealedRoad(position: Int?, name: String?, source: String? = null) {
        val canonical = RoadMatcher.canonical(name)
        if (canonical == null) {
            roadSource = null
            recomputeOdds()
            refreshAll()
            // OCR + AI both failed → show the 6-road manual picker
            promptManualRoad()
            return
        }
        val slot = (position ?: 2).coerceIn(1, 3)
        when (slot) {
            1 -> { r1 = canonical; lockedSlot = 1 }
            2 -> { r2 = canonical; lockedSlot = 2 }
            3 -> { r3 = canonical; lockedSlot = 3 }
        }
        roadSource = source ?: "screen"
        recomputeOdds()
        refreshAll()
    }

    /**
     * Session 4 — apply the race-strip layout (types + lengths) once the race starts.
     * Cross-checks the Session-2 visible road; low confidence is stored for later crop save.
     */
    fun applyStripLayout(result: RaceStripReader.StripResult) {
        stripTypes = result.roadTypes
        stripPx = result.roadPx
        stripTrackWidth = result.trackWidthPx
        stripConfidence = result.confidence
        captureStage = "race"
        if (!result.visibleRoadPresent) {
            // Visible banner type missing from strip — flag only; do not overwrite manual pick
            confidenceLabel = if (confidenceLabel.isBlank()) "STRIP MISMATCH" else confidenceLabel
        }
        // Session 6 — exact model order once layout is known
        recomputeModelTimes()
        refreshAll()
    }

    private fun recomputeModelTimes() {
        val cars = (if (laneCars.any { it != "—" }) laneCars else positionCars)
            .ifEmpty { vehicles }
        val segs = stripTypes.zip(stripPx).filter { it.second > 0 }
        val model = FinishTimeModel.compute(cars, segs)
        modelWinner = model?.modelWinner
        modelTimes = model?.modelTimesEncoded
    }

    /**
     * Session 5 — bind top-view lanes to card order and seed the sample buffer.
     */
    fun applyLaneCars(cardCars: List<String>, colourHints: List<String?> = emptyList()) {
        laneCars = TopViewTracker.assignLanes(cardCars, colourHints)
    }

    /** Append one top-view sample (~4 fps during the race window). */
    fun addTrackSample(timeMs: Long, x1: Float, x2: Float, x3: Float) {
        trackSamples = TopViewTracker.appendSample(
            trackSamples,
            TopViewTracker.Sample(timeMs, x1, x2, x3)
        )
    }

    /**
     * Run finish detection + optional speed-drift check once the race ends
     * (or when enough samples exist).
     */
    fun finaliseTrack(
        startX: Float = 0f,
        finishX: Float = (stripTrackWidth ?: 990.0).toFloat(),
        roadSegments: List<Pair<String, Double>> = stripTypes.zip(stripPx)
    ) {
        val (lo, hi, w) = TopViewTracker.geometry(startX, finishX)
        val st = TopViewTracker.TrackState(laneCars, trackSamples, lo, hi, w)
        val fin = TopViewTracker.detectFinish(st)
        finishOrder = fin.order
        if (fin.winner != null && winnerCar == null && !resultRecorded) {
            winnerCar = fin.winner
            winnerSource = AutoResult.SOURCE_TRACK
        }
        if (roadSegments.isNotEmpty() && trackSamples.size >= 2) {
            val drift = TopViewTracker.checkSpeedDrift(st, trackSamples, roadSegments, laneCars)
            speedDriftFlag = drift.flagMessage
        }
        if (modelWinner == null) recomputeModelTimes()
        captureStage = "finish"
    }

    /**
     * Session 6 — save the round even when the winner is unknown so the next
     * capture is never blocked. Manual WON/LOST/NO BET remains the override.
     */
    fun saveAutoOrUnknown() {
        if (resultRecorded) {
            advance()
            return
        }
        val backed = listOfNotNull(split?.car1, split?.car2?.takeIf { (split?.bet2 ?: 0) > 0 })
        val res = AutoResult.resolve(
            manualWinner = winnerCar,
            manualWon = wonFlag,
            noBet = noBet,
            trackOrder = finishOrder,
            trackWinner = finishOrder.firstOrNull() ?: winnerCar,
            positionCars = positionCars.ifEmpty { laneCars },
            balanceBefore = roundStartBalance,
            balanceAfter = balance,
            backedCars = backed
        )
        winnerCar = res.winner
        wonFlag = res.won
        winnerSource = res.winnerSource
        if (res.finishOrder.isNotEmpty()) finishOrder = res.finishOrder
        resultRecorded = true
        Prefs.setRoundUnrecorded(context, false)
        advance()
    }

    fun speedDriftMessage(): String? = speedDriftFlag

    /** Last-resort manual road picker (Session 2). Opens slot 2 by default. */
    fun promptManualRoad() {
        if (raceLocked) return
        val slot = if (lockedSlot in 1..3) lockedSlot else 2
        openRoadDropdown(slot)
        Toast.makeText(context, "Road unknown — pick the banner", Toast.LENGTH_SHORT).show()
    }

    private fun refreshAll() {
        btnR1?.text = "R1 ${short(r1)}"
        btnR2?.text = "R2 ${short(r2)}"
        btnR3?.text = "R3 ${short(r3)}"
        // Left to right: 1st, 2nd, 3rd
        // Rank labels only once cars are really ranked; before that they are screen positions
        val tags = if (rankingActive) listOf("1st", "2nd", "3rd") else listOf("L", "M", "R")
        btnV1?.text = "${tags[0]} ${vehicles.getOrElse(0) { "—" }}"
        btnV2?.text = "${tags[1]} ${vehicles.getOrElse(1) { "—" }}"
        btnV3?.text = "${tags[2]} ${vehicles.getOrElse(2) { "—" }}"
        btnBalance?.text = "💎 $balance"
        // Session 9 — state colour + one-glance card (static; no per-frame work)
        val phase = OverlayController.inferPhase(
            raceLocked, resultRecorded,
            stripTypes.isNotEmpty(), captureStage
        )
        tvState?.text = OverlayController.phaseLabel(phase)
        try {
            tvState?.setTextColor(android.graphics.Color.parseColor(OverlayController.phaseColor(phase)))
        } catch (_: Exception) { }
        val modelOrder = if (modelWinner != null && modelTimes != null) {
            modelTimes!!.split(",").mapNotNull { it.substringBefore(":").ifBlank { null } }
        } else emptyList()
        tvGlance?.text = OverlayController.glanceLine(
            car1 = split?.car1,
            bet1 = split?.bet1 ?: 0,
            confidenceLabel = confidenceLabel,
            confidence = confidence,
            observeOnly = Prefs.observeOnly(context),
            modelOrder = modelOrder,
            layoutKnown = stripTypes.isNotEmpty()
        )
        listOf(ivCar1, ivCar2, ivCar3).forEachIndexed { i, iv ->
            val res = Assets.CAR_RES[vehicles.getOrElse(i) { "—" }]
            if (res != null) iv?.setImageResource(res) else iv?.setImageDrawable(null)
        }

        val s = split
        fun fillOdds(i: Int, tvO: TextView?, tvB: TextView?, slot: LinearLayout?) {
            val car = vehicles.getOrElse(i) { "—" }
            val c = s?.cars?.firstOrNull { it.car == car }
            if (c != null) {
                tvO?.text = "${OddsEngine.formatOdds(c.odds)} · ${OddsEngine.formatEv(c.ev)}"
                tvB?.text = OddsEngine.formatBet(c.suggestedBet)
            } else {
                tvO?.text = "— · —"
                tvB?.text = ""
            }
            // Highlight = the car in the suggestion text: gold for the 1st pick, blue for the 2nd pick
            val isPick = s != null && c != null && car == s.car1
            val isSecond = s != null && c != null && car == s.car2 && s.bet2 > 0
            val won = resultRecorded && winnerCar == car
            val d = context.resources.displayMetrics.density
            val gd = GradientDrawable().apply {
                setColor(Color.parseColor("#1A2340"))
                cornerRadius = 12f * d
                setStroke(
                    ((if (isPick || isSecond || won) 2 else 1) * d).toInt(),
                    Color.parseColor(when {
                        won -> "#2ECC71"
                        isPick -> "#FCA311"
                        isSecond -> "#7FA7FF"
                        else -> "#2A2A4E"
                    })
                )
            }
            slot?.background = gd
        }
        // UI slots: left=1st=index0, mid=2nd=index1, right=3rd=index2
        fillOdds(0, tvOdds1, tvBet1, slotV1)
        fillOdds(1, tvOdds2, tvBet2, slotV2)
        fillOdds(2, tvOdds3, tvBet3, slotV3)

        val roadSet = lockedSlot != 0
        setEnabled(btnLock, roadSet && !raceLocked)
        setEnabled(btnWon, raceLocked)
        setEnabled(btnLost, raceLocked)
        setEnabled(btnNoBet, raceLocked)
        setEnabled(btnOrder, !raceLocked)
        setEnabled(btnRefreshOdds, !raceLocked)
        if (resultRecorded) {
            btnWon?.alpha = if (wonFlag == true) 1f else 0.45f
            btnLost?.alpha = if (wonFlag == false && winnerCar == null) 1f else 0.45f
            btnNoBet?.alpha = if (noBet) 1f else 0.45f
        }
        tvUnrecorded?.visibility = if (raceLocked && !resultRecorded) View.VISIBLE else View.GONE

        if (!postRace && lockedSlot != 0) {
            btnR1?.alpha = if (lockedSlot == 1) 1f else 0.4f
            btnR2?.alpha = if (lockedSlot == 2) 1f else 0.4f
            btnR3?.alpha = if (lockedSlot == 3) 1f else 0.4f
        } else {
            btnR1?.alpha = 1f; btnR2?.alpha = 1f; btnR3?.alpha = 1f
        }

        val warn = s?.warning
        if (confidence > 0) {
            fun rk(car: String?) = OddsEngine.ordinal(vehicles.indexOf(car) + 1)
            val betLine = s?.let {
                when {
                    it.car1 != null && it.car2 != null && it.bet2 > 0 ->
                        "Suggested: ${rk(it.car1)} [${it.car1}] ${"%,d".format(it.bet1)} 💎 + ${rk(it.car2)} [${it.car2}] ${"%,d".format(it.bet2)} 💎 = ${"%,d".format(it.totalBet)} 💎"
                    it.car1 != null ->
                        "Suggested: ${"%,d".format(it.bet1)} 💎 on ${rk(it.car1)} [${it.car1}] (conf $confidence%)"
                    else -> ""
                }
            } ?: ""
            val biasNote = if (Prefs.positionBiasEnabled(context)) "  📊 bias on" else ""
            tvConf?.text = betLine + (warn?.let { "  ⚠ $it" } ?: "") + biasNote
            tvConf?.setTextColor(Color.WHITE)
        } else {
            tvConf?.text = "Pick revealed road position (R1/R2/R3)"
            tvConf?.setTextColor(Color.parseColor("#8A8A9E"))
        }
    }

    private fun setEnabled(btn: Button?, enabled: Boolean) {
        btn?.isEnabled = enabled
        btn?.alpha = if (enabled) 1f else 0.45f
    }

    private fun short(r: String) = if (r == "???") "???" else r.take(5)

    fun removeAll() {
        removeLoadingOnly()
        try { view?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        view = null
        clearRefs()
    }

    private fun removeLoadingOnly() {
        try { loadingView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        loadingView = null
    }

    private fun clearRefs() {
        btnR1 = null; btnR2 = null; btnR3 = null
        btnV1 = null; btnV2 = null; btnV3 = null
        tvOdds1 = null; tvOdds2 = null; tvOdds3 = null
        tvBet1 = null; tvBet2 = null; tvBet3 = null
        slotV1 = null; slotV2 = null; slotV3 = null
        ivCar1 = null; ivCar2 = null; ivCar3 = null
        btnWon = null; btnLost = null; btnOrder = null; btnRefreshOdds = null
        btnExport = null; btnBalance = null
        btnNoBet = null; btnLock = null; tvUnrecorded = null
        tvConf = null; tvPrompt = null; dropdownArea = null; dropdownScroll = null
    }

    private fun baseParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
    }

    // ---- Session 3: state machine hooks ----

    fun syncPhaseFromMachine(snap: RoundStateMachine.Snapshot) {
        captureStage = when (snap.phase) {
            RoundStateMachine.Phase.BETTING -> "betting"
            RoundStateMachine.Phase.CLOSED -> "closed"
            RoundStateMachine.Phase.RACE -> "race"
            RoundStateMachine.Phase.FINISH -> "finish"
            RoundStateMachine.Phase.SAVED -> "saved"
            RoundStateMachine.Phase.IDLE -> "idle"
        }
        if (snap.phase == RoundStateMachine.Phase.CLOSED || snap.phase == RoundStateMachine.Phase.RACE) {
            raceLocked = true
        }
        val overlayPhase = RoundStateMachine.toOverlayPhase(snap.phase)
        tvState?.text = OverlayController.phaseLabel(overlayPhase)
        try {
            tvState?.setTextColor(android.graphics.Color.parseColor(OverlayController.phaseColor(overlayPhase)))
        } catch (_: Exception) { }
    }

    fun currentRevealedRoad(): String? {
        val r = revealedRoad()
        return r.takeIf { it != "???" && RoadMatcher.isKnown(it) }
    }

    fun applyLaneCarsFromPositions() {
        val cards = positionCars.ifEmpty { vehicles.filter { it != "—" } }
        if (cards.isEmpty()) return
        applyLaneCars(cards)
    }

    /**
     * Session 5 wiring: one top-view sample from a capture during RACE.
     * Uses a coarse horizontal split of the lower strip region as progress proxies
     * until a real colour-matched tracker is available.
     */
    fun addTrackSampleFromBitmap(bitmap: android.graphics.Bitmap) {
        // Crop lower strip band (top-view race area ~75–93% of screen height)
        val y0 = (bitmap.height * 0.75f).toInt().coerceIn(0, bitmap.height - 2)
        val y1 = (bitmap.height * 0.93f).toInt().coerceAtLeast(y0 + 1).coerceAtMost(bitmap.height)
        val h = y1 - y0
        val w = bitmap.width
        val crop = android.graphics.Bitmap.createBitmap(bitmap, 0, y0, w, h)
        val pixels = IntArray(w * h)
        crop.getPixels(pixels, 0, w, 0, 0, w, h)
        val xs = TopViewTracker.sampleLaneProgress(pixels, w, h)
        // Fallback: if no coloured sprite found, keep previous sample x (no synthetic time drift)
        val prev = trackSamples.lastOrNull()
        fun xOrPrev(i: Int, sample: Float) =
            if (sample > 1f) sample else (when (i) {
                0 -> prev?.x1
                1 -> prev?.x2
                else -> prev?.x3
            } ?: 0f)
        addTrackSample(
            timeMs = System.currentTimeMillis(),
            x1 = xOrPrev(0, xs[0]),
            x2 = xOrPrev(1, xs[1]),
            x3 = xOrPrev(2, xs[2])
        )
    }


    /** Show manual road picker only when race is nearly over and road is still unknown. */
    fun promptManualRoadIfNeeded() {
        val road = revealedRoad()
        if (road != "???" && RoadMatcher.isKnown(road)) return
        // Open R1 dropdown as the manual entry point (thumb-first)
        try {
            openRoadDropdown(1)
        } catch (_: Exception) { }
    }


    fun currentLaneOrPositionCars(): List<String> {
        val fromLane = laneCars.filter { it != "—" && it.isNotBlank() }
        if (fromLane.isNotEmpty()) return fromLane
        return positionCars.filter { it != "—" && it.isNotBlank() }
            .ifEmpty { vehicles.filter { it != "—" && it.isNotBlank() } }
    }

    /**
     * Session 4: finish-screen OCR hint. Does not override an existing track winner
     * unless winnerSource is still empty/unknown.
     */
    fun applyFinishScreenWinner(name: String) {
        if (name.isBlank()) return
        if (winnerCar != null && winnerSource == AutoResult.SOURCE_TRACK) return
        if (resultRecorded) return
        winnerCar = name
        winnerSource = AutoResult.SOURCE_SCREEN
        captureStage = "finish"
    }

}
