package com.chamet.guesser

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hybrid OCR: ML Kit on-device + optional AI vision fallback (Gemini / Groq / OpenRouter).
 *
 * Session 2 — pre-race reader:
 *  - Cars via ML Kit + CarMatcher; AI only if OCR fails.
 *  - Pools matched by screen column (PositionMapper), never by size.
 *  - Visible road from banner **text** (RoadMatcher), never texture/color.
 *  - roadSource = "screen" | "ai" | null (unknown → manual picker).
 */
object OCRHelper {

    data class OcrResult(
        val cars: List<String>,           // detected cars, left to right (no gaps)
        val pools: List<Long>,            // ALIGNED with positionCars (index0=left)
        val positionCars: List<String>,   // always 3 slots, index0=left pos1; empty = "—"
        val rawText: String,
        val source: String,               // mlkit | gemini | groq | openrouter
        val aiFallbackTriggered: Boolean = false,
        val balanceHint: Long? = null,
        val revealedRoadPosition: Int? = null,  // 1=R1 left, 2=R2 mid, 3=R3 right
        val revealedRoadName: String? = null,
        /** screen = local OCR banner, ai = vision model, null = unknown */
        val roadSource: String? = null
    )

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun readCarNames(context: Context, screenshot: Bitmap): List<String> {
        return readFull(context, screenshot).cars
    }

    suspend fun readFull(context: Context, screenshot: Bitmap): OcrResult {
        val mode = Prefs.ocrMode(context)
        return when (mode) {
            "ai" -> {
                val ai = AiOcrClient.analyze(context, screenshot)
                if (ai != null) fromAi(ai, false)
                else mlkitOnly(screenshot).copy(aiFallbackTriggered = true, source = "mlkit")
            }
            "mlkit" -> mlkitOnly(screenshot)
            else -> { // hybrid
                val ml = mlkitOnly(screenshot)
                if (ml.cars.size >= 2 && ml.pools.count { it > 0 } >= 2) {
                    // Cars/pools ok — still try AI for road if banner was missed
                    if (ml.revealedRoadName != null) ml
                    else {
                        val ai = AiOcrClient.analyze(context, screenshot)
                        if (ai != null && !ai.revealedRoadName.isNullOrBlank()) {
                            ml.copy(
                                revealedRoadPosition = ai.revealedRoadPosition
                                    ?: estimateSlot(screenshot.width, null),
                                revealedRoadName = RoadMatcher.canonical(ai.revealedRoadName),
                                roadSource = "ai",
                                aiFallbackTriggered = true
                            )
                        } else ml
                    }
                } else {
                    val ai = AiOcrClient.analyze(context, screenshot)
                    if (ai != null) fromAi(ai, true)
                    else ml.copy(aiFallbackTriggered = true)
                }
            }
        }
    }

    /** Pools keyed by car name, so a refresh can never shift pools onto the wrong car. */
    suspend fun readPoolsByCar(context: Context, screenshot: Bitmap): Map<String, Long> {
        val r = readFull(context, screenshot)
        return PositionMapper.poolsByCar(r.positionCars, r.pools)
    }

    /**
     * Sum of the three position pools. Useful **after betting closes** when pools
     * stop moving; during betting the sum drifts and must not be treated as truth.
     */
    fun poolSum(pools: List<Long>): Long = pools.sum()

    private fun fromAi(ai: AiOcrClient.AiResult, fallback: Boolean): OcrResult {
        val posCars = MutableList(3) { PositionMapper.EMPTY }
        val posPools = MutableList(3) { 0L }
        ai.cars.forEach { c ->
            val idx = (c.position - 1).coerceIn(0, 2)
            posCars[idx] = c.name
            posPools[idx] = c.pool
        }
        val roadName = RoadMatcher.canonical(ai.revealedRoadName)
        return OcrResult(
            cars = posCars.filter { it != PositionMapper.EMPTY },
            pools = posPools,
            positionCars = posCars,
            rawText = "",
            source = ai.provider,
            aiFallbackTriggered = fallback,
            balanceHint = ai.balance,
            revealedRoadPosition = ai.revealedRoadPosition,
            revealedRoadName = roadName,
            roadSource = if (roadName != null) "ai" else null
        )
    }

    private suspend fun mlkitOnly(screenshot: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        try {
            val image = InputImage.fromBitmap(screenshot, 0)
            val visionText: Text? = suspendCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }
            if (visionText == null) {
                return@withContext OcrResult(emptyList(), emptyList(), emptyList(), "", "mlkit")
            }

            val carItems = ArrayList<PositionMapper.Item<String>>()
            val poolItems = ArrayList<PositionMapper.Item<Long>>()
            var roadName: String? = null
            var roadCx: Float? = null
            val w = screenshot.width.toFloat()

            for (block in visionText.textBlocks) for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val cx = box.exactCenterX()
                val text = line.text

                CarMatcher.matchCars(listOf(text)).firstOrNull()?.let {
                    carItems.add(PositionMapper.Item(it, cx))
                }
                OddsEngine.findPools(text).forEach {
                    poolItems.add(PositionMapper.Item(it, cx))
                }

                // Banner text lives in the upper portion of the screen (above ~35%)
                if (roadName == null && box.exactCenterY() < screenshot.height * 0.40f) {
                    RoadMatcher.match(text)?.let {
                        roadName = it
                        roadCx = cx
                    }
                }
            }

            // Fallback: scan all lines if nothing in the upper band matched
            if (roadName == null) {
                for (block in visionText.textBlocks) for (line in block.lines) {
                    val box = line.boundingBox ?: continue
                    RoadMatcher.match(line.text)?.let {
                        roadName = it
                        roadCx = box.exactCenterX()
                        return@let
                    }
                }
            }

            val cols = PositionMapper.map(carItems, poolItems, screenshot.width)
            val slot = if (roadName != null) estimateSlot(screenshot.width, roadCx) else null
            OcrResult(
                cars = cols.detectedCars,
                pools = cols.pools,
                positionCars = cols.cars,
                rawText = visionText.text,
                source = "mlkit",
                revealedRoadPosition = slot,
                revealedRoadName = roadName,
                roadSource = if (roadName != null) "screen" else null
            )
        } catch (e: Exception) {
            OcrResult(emptyList(), emptyList(), emptyList(), "", "mlkit")
        }
    }

    /** Map horizontal center of the banner to R1 / R2 / R3. */
    fun estimateSlot(screenWidth: Int, centerX: Float?): Int {
        if (centerX == null || screenWidth <= 0) return 2
        val third = screenWidth / 3f
        return when {
            centerX < third -> 1
            centerX < third * 2 -> 2
            else -> 3
        }
    }
}
