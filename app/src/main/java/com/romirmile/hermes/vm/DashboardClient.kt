package com.romirmile.hermes.vm

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the Hermes dashboard that runs inside the guest VM.
 *
 * QEMU forwards the device's `127.0.0.1:9129` into the guest's in-VM relay, so this is ordinary HTTP
 * against loopback. The dashboard mints its own session token into the HTML it serves
 * (`window.__HERMES_SESSION_TOKEN__="…"`), so the app scrapes that marker from a real page load and
 * then proxies the `/api/` calls with the `X-Hermes-Session-Token` header. That proxy is what keeps the
 * shell page free of CORS problems: the page never talks to the dashboard itself, the app does.
 *
 * The reference app (Capacitor-based) did exactly the same against an embedded Python server; the
 * only difference here is where the server runs.
 */
class DashboardClient(val baseUrl: String = EngineStore.dashboardUrl()) {

    @Volatile private var cachedToken: String = ""
    @Volatile private var cachedAt: Long = 0L

    /**
     * The dashboard session token.
     *
     * The page scrape stays the source of truth; [forceRefresh] bypasses the short-lived cache, which
     * exists only so one shell action does not re-fetch the whole SPA for every API call it makes.
     */
    fun sessionToken(forceRefresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            synchronized(this) {
                if (cachedToken.isNotEmpty() && now - cachedAt < TOKEN_TTL_MS) return cachedToken
            }
        }
        val scraped = scrapeToken()
        synchronized(this) {
            if (scraped.isNotEmpty()) {
                cachedToken = scraped
                cachedAt = System.currentTimeMillis()
            } else if (forceRefresh) {
                cachedToken = ""
                cachedAt = 0L
            }
        }
        return scraped
    }

    /** True once the dashboard answers with its SPA (the token marker proves it is the real page). */
    fun isReady(): Boolean {
        val body = fetchPage()
        if (body != null) {
            val token = tokenFrom(body)
            if (token.isNotEmpty()) {
                synchronized(this) {
                    cachedToken = token
                    cachedAt = System.currentTimeMillis()
                }
                return true
            }
        }
        return apiStatus() != null
    }

    /** `/api/status` body, or null when the dashboard is not answering yet. */
    fun apiStatus(): JSONObject? = try {
        val envelope = request("GET", "/api/status", null, requireToken = false, timeoutMs = STATUS_TIMEOUT_MS)
        if (envelope.optBoolean("ok")) {
            envelope.optJSONObject("json")
                ?: JSONObject(envelope.optString("body", "{}"))
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Proxies one dashboard API call. Mirrors the reference method: only `/api/` paths are allowed,
     * the token header is added when asked for, and the result is the same JSON envelope the shell
     * parses (`ok`, `status`, `body`, `json` or `error`).
     */
    fun request(
        method: String,
        path: String,
        bodyJson: String?,
        requireToken: Boolean,
        timeoutMs: Int
    ): JSONObject {
        val result = JSONObject()
        var connection: HttpURLConnection? = null
        try {
            val safeMethod = method.trim().ifEmpty { "GET" }.uppercase()
            val safePath = path.trim().ifEmpty { "/api/status" }
            if (!safePath.startsWith("/api/")) {
                throw IllegalArgumentException("Only local Hermes API paths are allowed")
            }
            val timeout = if (timeoutMs > 0) timeoutMs else DEFAULT_TIMEOUT_MS
            connection = open(safeMethod, safePath, timeout)
            if (requireToken) {
                val token = sessionToken()
                if (token.isEmpty()) throw IllegalStateException("Dashboard session token unavailable")
                connection.setRequestProperty("X-Hermes-Session-Token", token)
            }
            val body = bodyJson.orEmpty()
            if (safeMethod != "GET" && body.isNotEmpty()) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            result.put("ok", status in 200..299)
            result.put("status", status)
            result.put("body", text)
            runCatching {
                result.put("json", if (text.isEmpty()) JSONObject() else JSONObject(text))
            }
        } catch (e: Exception) {
            Log.w(TAG, "dashboard request failed: ${e.message}")
            runCatching {
                result.put("ok", false)
                result.put("status", 0)
                result.put("error", e.message ?: e.toString())
            }
        } finally {
            connection?.disconnect()
        }
        return result
    }

    /** The dashboard page itself, or null when it is not serving yet. */
    fun fetchPage(locale: String = "en"): String? = try {
        val connection = open("GET", "/?locale=$locale", PAGE_TIMEOUT_MS)
        try {
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            if (status in 200..299) text else null
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun scrapeToken(): String {
        val body = fetchPage() ?: return ""
        return tokenFrom(body)
    }

    private fun tokenFrom(body: String): String {
        val start = body.indexOf(TOKEN_MARKER)
        if (start < 0) return ""
        val from = start + TOKEN_MARKER.length
        val end = body.indexOf('"', from)
        return if (end > from) body.substring(from, end) else ""
    }

    private fun open(method: String, path: String, timeoutMs: Int): HttpURLConnection {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = timeoutMs
            // English on every request, so a Chinese device locale can never pull Chinese UI.
            setRequestProperty("Accept-Language", "en")
            setRequestProperty("Accept", "application/json, text/html")
        }
        return connection
    }

    companion object {
        private const val TAG = "DashboardClient"

        /** The marker the dashboard writes into its own page. */
        const val TOKEN_MARKER = "window.__HERMES_SESSION_TOKEN__=\""

        private const val TOKEN_TTL_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val PAGE_TIMEOUT_MS = 8_000
        private const val STATUS_TIMEOUT_MS = 8_000
        private const val DEFAULT_TIMEOUT_MS = 15_000
    }
}
