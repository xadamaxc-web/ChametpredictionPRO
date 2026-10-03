package com.chamet.guesser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phase 2 — thin HTTP client for the Node control plane.
 * All network is opt-in via Prefs.serverEnabled.
 */
object ServerClient {

    private const val TAG = "ServerClient"

    data class LeaseInfo(val active: Boolean, val expiresAt: String?, val remainingMs: Long)
    data class QuotaInfo(val plan: String, val used: Int, val limit: Int, val remaining: Int)

    fun baseUrl(ctx: Context): String =
        Prefs.serverBaseUrl(ctx).trimEnd('/')

    suspend fun login(ctx: Context, email: String, password: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("email", email)
                    .put("password", password)
                val res = post(ctx, "/api/auth/login", body, auth = false) ?: return@withContext false
                Prefs.setServerToken(ctx, res.getString("token"))
                val user = res.getJSONObject("user")
                Prefs.setServerPlan(ctx, user.optString("plan", "free"))
                true
            } catch (e: Exception) {
                Log.w(TAG, "login: ${e.message}")
                false
            }
        }

    suspend fun requestLease(ctx: Context): LeaseInfo? = withContext(Dispatchers.IO) {
        try {
            val res = post(ctx, "/api/lease/request", JSONObject(), auth = true) ?: return@withContext null
            val lease = res.optJSONObject("lease")
            val expires = lease?.optString("expiresAt")
            Prefs.setLeaseExpiresAt(ctx, expires)
            LeaseInfo(true, expires, remainingMs(expires))
        } catch (e: Exception) {
            Log.w(TAG, "lease: ${e.message}")
            null
        }
    }

    suspend fun leaseStatus(ctx: Context): LeaseInfo = withContext(Dispatchers.IO) {
        try {
            val res = get(ctx, "/api/lease/status") ?: return@withContext localLease(ctx)
            val active = res.optBoolean("active", false)
            val lease = res.optJSONObject("lease")
            val expires = lease?.optString("expiresAt")
            if (expires != null) Prefs.setLeaseExpiresAt(ctx, expires)
            LeaseInfo(active, expires, res.optLong("remainingMs", remainingMs(expires)))
        } catch (_: Exception) {
            localLease(ctx)
        }
    }

    suspend fun syncRounds(ctx: Context, roundsJson: JSONArray, trackJson: JSONArray = JSONArray()): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().put("rounds", roundsJson).put("track", trackJson)
                post(ctx, "/api/rounds/sync", body, auth = true) != null
            } catch (e: Exception) {
                Log.w(TAG, "sync: ${e.message}")
                false
            }
        }

    suspend fun redeem(ctx: Context, code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val res = post(ctx, "/api/redeem", JSONObject().put("code", code), auth = true)
            if (res != null) {
                Prefs.setServerPlan(ctx, res.optString("plan", "pro"))
                true
            } else false
        } catch (e: Exception) {
            Log.w(TAG, "redeem: ${e.message}")
            false
        }
    }

    fun localLease(ctx: Context): LeaseInfo {
        val exp = Prefs.leaseExpiresAt(ctx)
        val rem = remainingMs(exp)
        return LeaseInfo(rem > 0, exp, rem)
    }

    /**
     * Phase 2 lock: when server mode is on, engine features require an active lease.
     * Phase 1 offline mode (server disabled) never locks.
     */
    fun engineUnlocked(ctx: Context): Boolean {
        if (!Prefs.serverEnabled(ctx)) return true
        return localLease(ctx).active
    }

    private fun remainingMs(expiresAt: String?): Long {
        if (expiresAt.isNullOrBlank()) return 0L
        return try {
            // ISO-8601
            val ms = java.time.Instant.parse(expiresAt).toEpochMilli()
            (ms - System.currentTimeMillis()).coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        }
    }

    private fun post(ctx: Context, path: String, body: JSONObject, auth: Boolean): JSONObject? {
        val url = URL(baseUrl(ctx) + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            if (auth) {
                val t = Prefs.serverToken(ctx)
                if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t")
            }
            doOutput = true
            connectTimeout = 12_000
            readTimeout = 20_000
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText() ?: return null
        if (code !in 200..299) {
            Log.w(TAG, "POST $path -> $code $text")
            return null
        }
        return JSONObject(text)
    }

    private fun get(ctx: Context, path: String): JSONObject? {
        val url = URL(baseUrl(ctx) + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            val t = Prefs.serverToken(ctx)
            if (t.isNotBlank()) setRequestProperty("Authorization", "Bearer $t")
            connectTimeout = 12_000
            readTimeout = 20_000
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText() ?: return null
        if (code !in 200..299) return null
        return JSONObject(text)
    }
}
