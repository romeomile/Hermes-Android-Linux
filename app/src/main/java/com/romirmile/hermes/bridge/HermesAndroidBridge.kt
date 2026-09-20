package com.romirmile.hermes.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import com.romirmile.hermes.vm.EngineController
import com.romirmile.hermes.vm.EngineStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLConnection
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Host-side surface the bridge needs from the screen that owns the WebView.
 *
 * Kept as an interface so the [HermesAndroidBridge] never touches Android UI types directly: every
 * call from JS arrives on the WebView's JavaBridge thread, while the file picker, the keyboard and
 * the activity are UI-thread objects.
 */
interface BridgeHost {

    fun runOnUiThread(block: () -> Unit)

    /** URL currently shown by the WebView, or null when there is none. */
    fun currentUrl(): String?

    /** Loads the bundled shell page again — this app's "home". */
    fun loadShellHome(): Boolean

    /** Raises the IME for the WebView. */
    fun showSoftKeyboard(): Boolean

    /** Opens the system document picker; returns content URIs (persistable read permission kept). */
    fun pickDocuments(onResult: (List<String>) -> Unit)

    /** Asks for the microphone runtime permission; reports the outcome once resolved. */
    fun requestMicrophonePermission(onResult: (Boolean) -> Unit)

    /** Whether the microphone permission is already granted. */
    fun hasMicrophonePermission(): Boolean
}

/**
 * The `HermesAndroid` JavascriptInterface the mobile shell talks to.
 *
 * Method names, signatures and return shapes are the reference app's (a Capacitor build that ran an
 * embedded Python server on `127.0.0.1:9129`). Here the same surface is mapped onto the Linux VM:
 *
 *  - `startEmbeddedAgent` / `getEmbeddedAgentStatus` / `runEmbeddedAgentCommand` report the engine
 *    that runs inside the guest, keeping the reference's JSON keys (`ok`, `available`, `running`,
 *    `port`, `url`, `uptime_seconds`, `error`, `api_status`) so the shell needs no changes;
 *  - `dashboardRequest` / `getDashboardSessionToken` proxy the guest dashboard natively, which is
 *    what keeps the shell page out of CORS trouble;
 *  - speech, the keyboard, the file picker and external links stay host-side, exactly as in the
 *    reference;
 *  - everything that only made sense next to the embedded runtime (bundled ADB client, bundled
 *    Node/TUI assets, Chaquopy Python, `callOpenAiChat`) answers a well-formed not-available JSON.
 *    None of these methods throw: a broken bridge would take the whole shell UI down with it.
 */
class HermesAndroidBridge(
    context: Context,
    private val host: BridgeHost
) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var engineStartedAt: Long = 0L
    @Volatile private var textToSpeech: TextToSpeech? = null
    @Volatile private var textToSpeechReady = false

    // ---------------------------------------------------------------------------------------------
    // Engine (the guest VM that hosts the dashboard)
    // ---------------------------------------------------------------------------------------------

    /**
     * The boot gate's first and only call. The shell shows a retry overlay while this returns
     * `running: false`, so it starts the VM when needed and waits for the dashboard to answer.
     */
    @JavascriptInterface
    fun startEmbeddedAgent(): String = try {
        if (EngineController.isDashboardReady()) {
            markEngineStarted()
            engineStatusJson()
        } else {
            // Bring the VM up if nobody has asked for it yet; the engine screen shows the progress.
            if (engineStartedAt == 0L) markEngineStarted()
            EngineController.start(appContext)
            val ready = awaitDashboard(START_TIMEOUT_MS)
            if (ready) engineStatusJson()
            else engineStatusJson(
                "the Linux VM is still starting: the dashboard has not answered on " +
                    "${EngineStore.dashboardUrl()} yet"
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "startEmbeddedAgent failed", e)
        engineStatusJson(e.message ?: "the Linux VM could not be started")
    }

    /** Dead in the shipped shell, kept for API compatibility. */
    @JavascriptInterface
    fun isEmbeddedAgentAvailable(): Boolean = try {
        EngineController.isDashboardReady()
    } catch (e: Exception) {
        Log.w(TAG, "isEmbeddedAgentAvailable failed", e)
        false
    }

    /** Dead in the shipped shell, kept for API compatibility. */
    @JavascriptInterface
    fun getEmbeddedAgentStatus(): String = try {
        engineStatusJson()
    } catch (e: Exception) {
        engineStatusJson(e.message ?: "the engine status is unavailable")
    }

    /**
     * The shell's status check and its model "test" both send `diagnose`; the reference ran those
     * through the embedded Python backend. Here they report the VM engine, and anything that has no
     * equivalent answers a clear not-available JSON with the same keys.
     */
    @JavascriptInterface
    fun runEmbeddedAgentCommand(commandJson: String?): String = try {
        val command = runCatching {
            JSONObject(commandJson.orEmpty().ifEmpty { "{}" }).optString("command")
        }.getOrDefault("")
        when (command.trim().lowercase(Locale.US)) {
            "", "status", "version", "diagnose", "doctor" -> engineStatusJson()
            "start", "run", "serve" -> startEmbeddedAgent()
            else -> engineStatusJson("the command \"$command\" is not available in this app")
        }
    } catch (e: Exception) {
        Log.w(TAG, "runEmbeddedAgentCommand failed", e)
        engineStatusJson(e.message ?: "the command is unavailable")
    }

    /**
     * The status envelope the shell renders: same keys as the reference app, but the values describe
     * the Linux VM and the dashboard inside it instead of an in-process Python server.
     */
    private fun engineStatusJson(error: String = ""): String = engineStatusObject(error).toString()

    private fun engineStatusObject(error: String = ""): JSONObject {
        val ready = runCatching { EngineController.isDashboardReady() }.getOrDefault(false)
        val status = if (ready) runCatching { EngineController.dashboard.apiStatus() }.getOrNull() else null
        val uptimeSeconds = if (engineStartedAt > 0L) {
            ((System.currentTimeMillis() - engineStartedAt) / 1000.0)
        } else {
            0.0
        }
        return JSONObject().apply {
            put("ok", ready)
            put("available", true)
            put("running", ready)
            put("port", EngineStore.DASHBOARD_PORT)
            put("url", EngineStore.dashboardUrl())
            put("uptime_seconds", uptimeSeconds)
            put("thread_alive", ready)
            put("files_dir", appContext.filesDir.absolutePath)
            put("engine", "Linux VM (Alpine)")
            put("error", error)
            if (status != null) put("api_status", status)
        }
    }

    private fun markEngineStarted() {
        if (engineStartedAt == 0L) engineStartedAt = System.currentTimeMillis()
    }

    private fun awaitDashboard(timeoutMs: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (EngineController.isDashboardReady()) return true
            runCatching { Thread.sleep(500) }
        }
        return EngineController.isDashboardReady()
    }

    // ---------------------------------------------------------------------------------------------
    // Dashboard (the SPA served by the guest)
    // ---------------------------------------------------------------------------------------------

    /** Scrapes `window.__HERMES_SESSION_TOKEN__="…"` out of the dashboard's own page. */
    @JavascriptInterface
    fun getDashboardSessionToken(): String = try {
        EngineController.dashboard.sessionToken()
    } catch (e: Exception) {
        Log.w(TAG, "getDashboardSessionToken failed", e)
        ""
    }

    /**
     * Proxies the `/api/` paths to the dashboard with the `X-Hermes-Session-Token` header. The shell page
     * therefore never contacts the dashboard cross-origin, which is what avoids CORS entirely.
     */
    @JavascriptInterface
    fun dashboardRequest(
        method: String?,
        path: String?,
        bodyJson: String?,
        requireToken: Boolean,
        timeoutMs: Int
    ): String = try {
        EngineController.dashboard.request(
            method = method.orEmpty().ifBlank { "GET" },
            path = path.orEmpty().ifBlank { "/api/status" },
            bodyJson = bodyJson,
            requireToken = requireToken,
            timeoutMs = timeoutMs
        ).toString()
    } catch (e: Exception) {
        Log.w(TAG, "dashboardRequest failed", e)
        JSONObject().apply {
            put("ok", false)
            put("status", 0)
            put("error", e.message ?: "the dashboard request failed")
        }.toString()
    }

    /** Dead in the shipped shell (the JS keeps its own config in localStorage). */
    @JavascriptInterface
    fun getMobileChatConfig(): String = JSONObject().apply {
        put("apiKey", "")
        put("baseUrl", prefs.getString(KEY_CHAT_BASE_URL, ""))
        put("model", EngineStore(appContext).model)
    }.toString()

    /**
     * Dead in the shipped shell. Only the endpoint and the model name are kept: this app never
     * stores a provider credential — the user enters it in the dashboard, where it stays.
     */
    @JavascriptInterface
    fun saveMobileChatConfig(configJson: String?): Boolean = try {
        val parsed = JSONObject(configJson.orEmpty().ifEmpty { "{}" })
        val baseUrl = parsed.optString("baseUrl")
        val model = parsed.optString("model")
        prefs.edit().putString(KEY_CHAT_BASE_URL, baseUrl).apply()
        EngineStore(appContext).model = model
        true
    } catch (e: Exception) {
        Log.w(TAG, "saveMobileChatConfig failed", e)
        false
    }

    /**
     * Deliberately not ported. The reference sent the user's provider key from the app process; in
     * this app the provider key belongs to the guest dashboard's own configuration and never passes
     * through the host.
     */
    @JavascriptInterface
    fun callOpenAiChat(requestJson: String?, timeoutMs: Int): String = JSONObject().apply {
        put("ok", false)
        put("status", 0)
        put("body", "")
        put("error", "not available in this app: the model provider and its key are configured " +
            "inside the Linux VM dashboard, not in the host process")
    }.toString()

    // ---------------------------------------------------------------------------------------------
    // Navigation / keyboard
    // ---------------------------------------------------------------------------------------------

    /** Loads the bundled shell again ("return to the mobile UI" button in terminal mode). */
    @JavascriptInterface
    fun returnToMobileHome(): Boolean = try {
        host.runOnUiThread { host.loadShellHome() }
        true
    } catch (e: Exception) {
        Log.w(TAG, "returnToMobileHome failed", e)
        false
    }

    @JavascriptInterface
    fun showSoftKeyboard(): Boolean = try {
        host.showSoftKeyboard()
        true
    } catch (e: Exception) {
        Log.w(TAG, "showSoftKeyboard failed", e)
        false
    }

    @JavascriptInterface
    fun openExternalUrl(url: String?): Boolean {
        val value = url.orEmpty().trim()
        if (value.isEmpty()) return false
        val started = booleanArrayOf(false)
        val latch = CountDownLatch(1)
        host.runOnUiThread {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(value)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
                started[0] = true
            } catch (e: Exception) {
                Log.w(TAG, "no activity can open $value", e)
            } finally {
                latch.countDown()
            }
        }
        runCatching { latch.await(2, TimeUnit.SECONDS) }
        return started[0]
    }

    @JavascriptInterface
    fun requestMicrophoneAccess(timeoutMs: Int): Boolean = try {
        if (host.hasMicrophonePermission()) {
            true
        } else {
            val granted = booleanArrayOf(false)
            val latch = CountDownLatch(1)
            host.requestMicrophonePermission { allowed ->
                granted[0] = allowed
                latch.countDown()
            }
            runCatching { latch.await((if (timeoutMs > 0) timeoutMs else 60_000).toLong(), TimeUnit.MILLISECONDS) }
            granted[0]
        }
    } catch (e: Exception) {
        Log.w(TAG, "requestMicrophoneAccess failed", e)
        false
    }

    /** Dead in the shipped shell, kept for API compatibility. */
    @JavascriptInterface
    fun recordSpeechText(optionsJson: String?, timeoutMs: Int): String = JSONObject().apply {
        put("ok", false)
        put("text", "")
        put("error", "not available in this app: dictation is handled by the agent inside the " +
            "Linux VM dashboard, not by the host")
    }.toString()

    // ---------------------------------------------------------------------------------------------
    // Speech (Android TextToSpeech, English by default)
    // ---------------------------------------------------------------------------------------------

    @JavascriptInterface
    fun speakText(optionsJson: String?, timeoutMs: Int): String {
        val result = JSONObject()
        try {
            val options = if (optionsJson.isNullOrEmpty()) JSONObject() else JSONObject(optionsJson)
            val text = options.optString("text").trim()
            val language = options.optString("language").trim().ifEmpty { DEFAULT_SPEECH_LANGUAGE }
            if (text.isEmpty()) throw IllegalStateException("nothing to speak")
            speak(text, language, if (timeoutMs > 0) timeoutMs else 30_000)
            result.put("ok", true)
            result.put("error", "")
        } catch (e: Exception) {
            Log.w(TAG, "speakText failed", e)
            runCatching {
                result.put("ok", false)
                result.put("error", e.message ?: "speech playback failed")
            }
        }
        return result.toString()
    }

    @JavascriptInterface
    fun stopSpeaking(): Boolean {
        try {
            host.runOnUiThread { runCatching { textToSpeech?.stop() } }
        } catch (e: Exception) {
            Log.w(TAG, "stopSpeaking failed", e)
        }
        return true
    }

    private fun speak(text: String, language: String, timeoutMs: Int) {
        val engine = ensureTextToSpeech(if (timeoutMs > 0) timeoutMs else 30_000)
        val locale = runCatching { Locale.forLanguageTag(language) }.getOrNull() ?: Locale.US
        val applied = engine.setLanguage(if (locale.language.isEmpty()) Locale.US else locale)
        if (applied == TextToSpeech.LANG_MISSING_DATA || applied == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
        val started = CountDownLatch(1)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = started.countDown()
            override fun onDone(utteranceId: String?) = Unit
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = started.countDown()
            override fun onError(utteranceId: String?, errorCode: Int) = started.countDown()
        })
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "hermes-${System.currentTimeMillis()}")
        // Wait for playback to start (not for it to finish): the shell only needs to know that the
        // device accepted the request, and blocking the JS thread for the whole utterance would
        // freeze the shell UI.
        if (!started.await(SPEAK_START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("speech playback start timed out")
        }
    }

    @Synchronized
    private fun ensureTextToSpeech(timeoutMs: Int): TextToSpeech {
        textToSpeech?.let { if (textToSpeechReady) return it }
        val initLatch = CountDownLatch(1)
        val status = intArrayOf(TextToSpeech.ERROR)
        val engine = TextToSpeech(appContext) { initStatus ->
            status[0] = initStatus
            initLatch.countDown()
        }
        if (!initLatch.await(minOf(timeoutMs.toLong(), 10_000L), TimeUnit.MILLISECONDS)) {
            runCatching { engine.shutdown() }
            throw IllegalStateException("speech playback initialisation timed out")
        }
        if (status[0] != TextToSpeech.SUCCESS) {
            runCatching { engine.shutdown() }
            throw IllegalStateException("speech playback initialisation failed")
        }
        textToSpeech = engine
        textToSpeechReady = true
        return engine
    }

    // ---------------------------------------------------------------------------------------------
    // Files (scoped storage: the picker hands out content URIs, nothing is copied to public storage)
    // ---------------------------------------------------------------------------------------------

    @JavascriptInterface
    fun selectPaths(optionsJson: String?, timeoutMs: Int): String {
        val paths = JSONArray()
        return try {
            val selected = mutableListOf<String>()
            val latch = CountDownLatch(1)
            host.pickDocuments { uris ->
                selected.clear()
                selected.addAll(uris)
                latch.countDown()
            }
            latch.await((if (timeoutMs > 0) timeoutMs else 120_000).toLong(), TimeUnit.MILLISECONDS)
            selected.forEach { paths.put(it) }
            JSONObject().apply {
                put("ok", true)
                put("paths", paths)
            }.toString()
        } catch (e: Exception) {
            Log.w(TAG, "selectPaths failed", e)
            JSONObject().apply {
                put("ok", false)
                put("paths", paths)
                put("error", e.message ?: "the file picker failed")
            }.toString()
        }
    }

    /** Reads a picked `content://` URI or a plain path and returns it as a base64 data URL. */
    @JavascriptInterface
    fun readFileDataUrl(path: String?): String = try {
        val value = path.orEmpty().trim()
        if (value.isEmpty()) throw IllegalArgumentException("File not found")
        val (bytes, mime) = readBytes(value)
        JSONObject().apply {
            put("ok", true)
            put("dataUrl", "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
        }.toString()
    } catch (e: Exception) {
        Log.w(TAG, "readFileDataUrl failed", e)
        JSONObject().apply {
            put("ok", false)
            put("dataUrl", "")
            put("error", e.message ?: "the file could not be read")
        }.toString()
    }

    private fun readBytes(path: String): Pair<ByteArray, String> {
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            val resolver = appContext.contentResolver
            val mime = resolver.getType(uri)
                ?: guessMime(displayName(uri) ?: path)
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("File not found")
            if (bytes.size > MAX_INLINE_BYTES) {
                throw IllegalArgumentException("the file is too large to read into the app (limit $MAX_INLINE_MB MB)")
            }
            return bytes to mime
        }
        val file = File(path)
        if (!file.isFile) throw IllegalArgumentException("File not found")
        if (file.length() > MAX_INLINE_BYTES) {
            throw IllegalArgumentException("the file is too large to read into the app (limit $MAX_INLINE_MB MB)")
        }
        return file.readBytes() to guessMime(file.name)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    private fun guessMime(name: String): String =
        URLConnection.guessContentTypeFromName(name)?.takeIf { it.isNotEmpty() }
            ?: "application/octet-stream"

    // ---------------------------------------------------------------------------------------------
    // Phone control (ADB). The reference bundled an ADB client; this app does not ship one, so the
    // four methods below answer a clear not-available JSON instead of throwing.
    // ---------------------------------------------------------------------------------------------

    @JavascriptInterface
    fun getAndroidAdbStatus(): String = adbUnavailable("this app does not ship an ADB client")

    @JavascriptInterface
    fun pairAndroidAdb(host: String?, port: Int, pairingCode: String?): String =
        adbUnavailable("this app does not ship an ADB client")

    @JavascriptInterface
    fun connectAndroidAdb(host: String?, port: Int): String =
        adbUnavailable("this app does not ship an ADB client")

    @JavascriptInterface
    fun runAndroidAdbShell(serial: String?, command: String?): String =
        adbUnavailable("this app does not ship an ADB client")

    private fun adbUnavailable(reason: String): String = JSONObject().apply {
        put("ok", false)
        put("available", false)
        put("connected", false)
        put("exitCode", -1)
        put("timedOut", false)
        put("output", "")
        put("devices", JSONArray())
        put("error", "phone control over ADB is not available: $reason")
    }.toString()

    /** Opening the system settings is a plain Android action and stays real. */
    @JavascriptInterface
    fun openDeveloperOptions(): Boolean = openSettings("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")

    @JavascriptInterface
    fun openWirelessDebuggingSettings(): Boolean {
        if (openSettings("android.settings.WIRELESS_DEBUGGING_SETTINGS")) return true
        return openDeveloperOptions()
    }

    private fun openSettings(action: String): Boolean = try {
        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        appContext.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "cannot open $action", e)
        false
    }

    /** Releases the TTS engine; called when the screen goes away. */
    fun shutdown() {
        runCatching {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        }
        textToSpeech = null
        textToSpeechReady = false
    }

    companion object {
        const val INTERFACE_NAME = "HermesAndroid"
        private const val TAG = "HermesAndroidBridge"
        private const val PREFS = "hermes_mobile_bridge"
        private const val KEY_CHAT_BASE_URL = "chat_base_url"

        /** The reference default was zh-CN; this app is English only. */
        private const val DEFAULT_SPEECH_LANGUAGE = "en-US"

        private const val START_TIMEOUT_MS = 20_000
        private const val SPEAK_START_TIMEOUT_MS = 5_000L
        private const val MAX_INLINE_MB = 24
        private const val MAX_INLINE_BYTES = MAX_INLINE_MB * 1024 * 1024
    }
}
