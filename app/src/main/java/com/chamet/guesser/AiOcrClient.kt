package com.chamet.guesser

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Multi-provider vision OCR with fallback: slot1 → slot2 → slot3 → null (caller uses ML Kit).
 */
object AiOcrClient {

    data class AiCar(
        val position: Int, // 1=left, 2=mid, 3=right
        val name: String,
        val pool: Long,
        val odds: String // e.g. "1.4/2.4"
    )

    data class AiResult(
        val cars: List<AiCar>,
        val totalPool: Long,
        val balance: Long?,
        val provider: String,
        val revealedRoadPosition: Int? = null, // 1/2/3
        val revealedRoadName: String? = null
    )

    private const val PROMPT = """Read this Chamet Race screenshot. Return ONLY JSON:
{
  "cars": [
    {"position": 1, "name": "ATV", "pool": 3327187, "odds": "1.4/2.4"},
    {"position": 2, "name": "CAR", "pool": 574208, "odds": "8.2/4.2"},
    {"position": 3, "name": "STOCK", "pool": 841561, "odds": "5.6/5.1"}
  ],
  "roads": [
    {"position": 1, "name": "Dirt", "revealed": false},
    {"position": 2, "name": "Highway", "revealed": true},
    {"position": 3, "name": "Dirt", "revealed": false}
  ],
  "revealed_road_position": 2,
  "revealed_road_name": "Highway",
  "total_pool": 4742956,
  "balance": 478677
}
Rules:
- cars position = physical column on screen (1=left, 2=middle, 3=right).
- The yellow banner near the top shows the REVEALED road name.
- Banner horizontal placement: left third→position 1, middle third→position 2, right third→position 3.
- Set revealed:true only on the revealed road; others false or unknown names ok.
- Use short car names: ATV, CAR, SUV, ORV, STOCK, SUPER, SPORTS, MONSTER, MOTOR.
- Return ONLY JSON, no markdown.
"""

    private const val PROMPT_ROAD_ONLY = """Read this Chamet Race ROAD BANNER crop (top of screen). Return ONLY JSON:
{
  "revealed_road_position": 2,
  "revealed_road_name": "Highway",
  "cars": [],
  "total_pool": 0,
  "balance": null
}
Rules:
- The yellow banner shows the REVEALED road name (Dirt, Bumpy, Potholes, Desert, Highway, Expressway).
- Banner horizontal placement: left third→1, middle→2, right→3.
- Return ONLY JSON, no markdown.
"""

    suspend fun analyze(
        context: Context,
        bitmap: Bitmap,
        roadOnly: Boolean = false
    ): AiResult? = withContext(Dispatchers.IO) {
        val b64 = bitmapToBase64(bitmap)
        val prompt = if (roadOnly) PROMPT_ROAD_ONLY else PROMPT
        for (i in 1..3) {
            val slot = Prefs.slot(context, i)
            if (!slot.enabled || slot.apiKey.isBlank()) continue
            if (Prefs.isExhausted(context, slot.id)) continue
            try {
                val result = when (slot.id) {
                    "gemini" -> callGemini(slot.model, slot.apiKey, b64, prompt)
                    "groq" -> callOpenAiStyle(
                        "https://api.groq.com/openai/v1/chat/completions",
                        slot.model, slot.apiKey, b64, vision = true, prompt = prompt
                    )
                    "openrouter" -> callOpenAiStyle(
                        "https://openrouter.ai/api/v1/chat/completions",
                        slot.model, slot.apiKey, b64, vision = true, prompt = prompt
                    )
                    else -> null
                }
                if (result != null) {
                    Prefs.bumpCallCount(context, slot.id)
                    return@withContext result.copy(provider = slot.id)
                }
            } catch (e: Exception) {
                if (e.message?.contains("429") == true) {
                    Prefs.markExhausted(context, slot.id)
                }
            }
        }
        null
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val scaled = if (bitmap.width > 1280) {
            val h = (bitmap.height * 1280f / bitmap.width).toInt()
            Bitmap.createScaledBitmap(bitmap, 1280, h, true)
        } else bitmap
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun callGemini(model: String, key: String, b64: String, prompt: String = PROMPT): AiResult? {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
        )
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().apply {
                        put("inline_data", JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", b64))
                    }))
            }))
        }
        val resp = httpPost(url, body.toString(), null)
        if (resp.first == 429) throw Exception("429")
        if (resp.first !in 200..299) return null
        val text = JSONObject(resp.second)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text") ?: return null
        return parseJson(text, "gemini")
    }

    private fun callOpenAiStyle(
        endpoint: String,
        model: String,
        key: String,
        b64: String,
        vision: Boolean,
        prompt: String = PROMPT
    ): AiResult? {
        val url = URL(endpoint)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
            })
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", content)))
            put("max_tokens", 1024)
        }
        val resp = httpPost(url, body.toString(), "Bearer $key")
        if (resp.first == 429) throw Exception("429")
        if (resp.first !in 200..299) return null
        val text = JSONObject(resp.second)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content") ?: return null
        return parseJson(text, "openai")
    }

    private fun httpPost(url: URL, body: String, auth: String?): Pair<Int, String> {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            if (auth != null) setRequestProperty("Authorization", auth)
            doOutput = true
            connectTimeout = 20000
            readTimeout = 30000
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        return code to text
    }

    private fun parseJson(raw: String, provider: String): AiResult? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = JSONObject(cleaned.substring(start, end + 1))
        val arr = obj.optJSONArray("cars") ?: return null
        val cars = mutableListOf<AiCar>()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val rawName = c.optString("name", "")
            val canonical = CarMatcher.fuzzyMatch(rawName)
                ?: CarMatcher.matchCars(listOf(rawName)).firstOrNull()
                ?: rawName
            cars.add(
                AiCar(
                    position = c.optInt("position", i + 1),
                    name = canonical,
                    pool = c.optLong("pool", 0L),
                    odds = c.optString("odds", "")
                )
            )
        }
        cars.sortBy { it.position }
        var revPos: Int? = if (obj.has("revealed_road_position")) obj.optInt("revealed_road_position") else null
        var revName: String? = obj.optString("revealed_road_name", null)
        if ((revPos == null || revPos == 0) && obj.has("roads")) {
            val roads = obj.optJSONArray("roads")
            if (roads != null) {
                for (i in 0 until roads.length()) {
                    val rd = roads.getJSONObject(i)
                    if (rd.optBoolean("revealed", false)) {
                        revPos = rd.optInt("position", 0).takeIf { it in 1..3 }
                        revName = rd.optString("name", null)
                        break
                    }
                }
            }
        }
        if (revPos == null || revPos !in 1..3) revPos = 2 // default middle
        return AiResult(
            cars = cars,
            totalPool = obj.optLong("total_pool", cars.sumOf { it.pool }),
            balance = if (obj.has("balance") && !obj.isNull("balance")) obj.optLong("balance") else null,
            provider = provider,
            revealedRoadPosition = revPos,
            revealedRoadName = revName
        )
    }
}
