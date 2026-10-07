package com.romirmile.hermes.data

import com.romirmile.hermes.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/** Why a stream failed — mapped to a localized message by the view model. */
enum class FailureKind { OFFLINE, HOST_UNREACHABLE, TIMEOUT, CONNECTION_LOST, HTTP, UNKNOWN }

/** Events produced while streaming one assistant turn from the Hermes API server. */
sealed interface StreamEvent {
    data class Delta(val text: String) : StreamEvent
    data class ToolStarted(val tool: String, val preview: String) : StreamEvent
    data class ToolFinished(
        val tool: String,
        val duration: Double?,
        val error: Boolean,
        val preview: String
    ) : StreamEvent

    /** [retryable] is true when re-sending the same turn is safe and may succeed. */
    data class Failed(
        val kind: FailureKind,
        val detail: String,
        val retryable: Boolean,
        /** HTTP status when the gateway answered with an error status, null for transport errors. */
        val httpCode: Int? = null
    ) : StreamEvent

    data object Completed : StreamEvent
}

/**
 * Thin client for Hermes: streaming chat completions over its /v1 HTTP API.
 *
 * Streaming uses a plain HttpURLConnection + hand-rolled SSE parsing: no extra
 * dependency, and the connection can be force-closed by [stop] so a blocking read
 * on a background thread unblocks immediately when the user taps Stop.
 */
class HermesClient {

    @Volatile
    private var active: HttpURLConnection? = null

    @Volatile
    private var stopped = false

    /** Force-close the in-flight request; the reader then throws and the flow ends quietly. */
    fun stop() {
        stopped = true
        runCatching { active?.disconnect() }
        active = null
    }

    fun streamChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        turns: List<ChatTurn>,
        idempotencyKey: String? = null,
        gatewaySessionId: String? = null,
        memoryKey: String? = null
    ): Flow<StreamEvent> = flow {
        stopped = false
        val conn = (URL(chatUrl(baseUrl)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            // No read timeout on purpose: this is one long-lived streaming response from an agent that
            // runs under QEMU emulation, and a cold turn legitimately takes minutes before its first
            // byte. A finite timeout here abandons a live request and reports it as a dropped
            // connection. Calling stop() (which disconnects the connection) is how a turn is cancelled.
            readTimeout = 0
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            // A retry of the SAME turn must not run the agent twice: the gateway dedupes on this key.
            if (!idempotencyKey.isNullOrBlank()) setRequestProperty("Idempotency-Key", idempotencyKey)
            // Bind this turn to a Hermes session, so each app chat is a real gateway session.
            if (!gatewaySessionId.isNullOrBlank()) {
                setRequestProperty("X-Hermes-Session-Id", gatewaySessionId)
            }
            // Stable per-install scope for long-term memory across sessions.
            if (!memoryKey.isNullOrBlank()) setRequestProperty("X-Hermes-Session-Key", memoryKey)
        }
        active = conn
        try {
            val payload = JSONObject().apply {
                // The model alias on the user's Hermes agent; "hermes-agent" is the api_server default.
                put("model", model.ifBlank { DEFAULT_MODEL })
                put("stream", true)
                put("messages", JSONArray().apply {
                    turns.forEach { turn ->
                        put(JSONObject().put("role", turn.role).put("content", contentFor(turn)))
                    }
                })
            }.toString()

            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                emit(
                    StreamEvent.Failed(
                        kind = FailureKind.HTTP,
                        detail = httpError(code, readError(conn)),
                        retryable = code == 429 || code >= 500,
                        httpCode = code
                    )
                )
                return@flow
            }

            conn.inputStream.bufferedReader().use { reader ->
                var eventName = ""
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith(":") -> Unit // keepalive comment
                        line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                        line.isBlank() -> eventName = ""
                        line.startsWith("data:") -> {
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") {
                                emit(StreamEvent.Completed)
                                eventName = ""
                                continue
                            }
                            val obj = runCatching { JSONObject(data) }.getOrNull()
                            if (obj != null) {
                                val toolEvent = toolEvent(eventName, obj)
                                if (toolEvent != null) emit(toolEvent) else {
                                    val text = deltaText(obj)
                                    if (!text.isNullOrEmpty()) emit(StreamEvent.Delta(text))
                                }
                            }
                            eventName = ""
                        }
                    }
                }
            }
            emit(StreamEvent.Completed)
        } catch (t: Throwable) {
            if (!stopped && t !is CancellationException) {
                emit(
                    StreamEvent.Failed(
                        kind = failureKind(t),
                        detail = t.message ?: t.javaClass.simpleName,
                        retryable = t is IOException
                    )
                )
            }
        } finally {
            active = null
            runCatching { conn.disconnect() }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * The user points the app at whatever gateway they have: a Hermes API server answers `/health`
     * with a version, a plain OpenAI-compatible endpoint does not — so fall back to `/models`
     * instead of reporting the gateway as broken.
     */
    data class Connection(val hermesVersion: String?, val modelCount: Int)

    suspend fun checkConnection(baseUrl: String, apiKey: String): Result<Connection> =
        withContext(Dispatchers.IO) {
            val version = runCatching { readVersion(baseUrl, apiKey) }.getOrNull()
            if (version != null) Result.success(Connection(version, 0))
            else listModels(baseUrl, apiKey).map { Connection(null, it.size) }
        }

    /** GET /health → the gateway's version string. */
    private fun readVersion(baseUrl: String, apiKey: String): String {
        val conn = open("GET", healthUrl(baseUrl), apiKey)
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException(httpError(code, body))
            return JSONObject(body).optString("version", "?").ifBlank { "?" }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** GET /models → the model ids the gateway advertises. */
    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = open("GET", modelsUrl(baseUrl), apiKey)
                try {
                    val code = conn.responseCode
                    val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) throw IllegalStateException(httpError(code, body))
                    val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
                    }
                } finally {
                    runCatching { conn.disconnect() }
                }
            }
        }

    /**
     * Plain text turns are sent as a bare string; a turn carrying an inline image is sent as a
     * content-part array (text + image_url with a data: URL), which Hermes accepts.
     */
    private fun contentFor(turn: ChatTurn): Any =
        if (turn.imageDataUrl.isNullOrBlank()) {
            turn.text
        } else {
            JSONArray().apply {
                if (turn.text.isNotBlank()) {
                    put(JSONObject().put("type", "text").put("text", turn.text))
                }
                put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", turn.imageDataUrl))
                )
            }
        }

    /**
     * Socket-level failures all look alike to the user; classify them so the UI can say something
     * human instead of leaking "Software caused connection abort" from the Java stack.
     */
    private fun failureKind(t: Throwable): FailureKind = when {
        t is UnknownHostException || t is ConnectException -> FailureKind.HOST_UNREACHABLE
        t is SocketTimeoutException -> FailureKind.TIMEOUT
        t is IOException -> {
            val m = (t.message ?: "").lowercase()
            when {
                m.contains("abort") || m.contains("reset") || m.contains("broken pipe") ||
                    m.contains("closed") || m.contains("epipe") || m.contains("econnaborted") ||
                    m.contains("connection lost") -> FailureKind.CONNECTION_LOST
                m.contains("timed out") || m.contains("timeout") -> FailureKind.TIMEOUT
                m.contains("unreachable") || m.contains("refused") || m.contains("no route")
                    || m.contains("failed to connect") -> FailureKind.HOST_UNREACHABLE
                else -> FailureKind.CONNECTION_LOST
            }
        }
        else -> FailureKind.UNKNOWN
    }

    private fun open(method: String, url: String, apiKey: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }

    private fun deltaText(obj: JSONObject): String? {
        val choices = obj.optJSONArray("choices") ?: return null
        val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return null
        if (delta.isNull("content")) return null
        return delta.optString("content", "")
    }

    private fun toolEvent(eventName: String, obj: JSONObject): StreamEvent? {
        val name = obj.optString("tool").ifBlank {
            obj.optString("tool_name").ifBlank { obj.optString("name") }
        }
        val looksLikeTool = eventName.contains("tool") || obj.has("tool") || obj.has("tool_name")
        if (!looksLikeTool || name.isBlank()) return null

        val preview = obj.optString("preview").ifBlank {
            obj.optString("arguments").ifBlank { obj.optString("args") }
        }
        val phase = obj.optString("phase").ifBlank { eventName }
        val finished = phase.contains("complete", true) ||
            phase.contains("finish", true) ||
            phase.contains("end", true)
        return if (finished) {
            StreamEvent.ToolFinished(
                tool = name,
                duration = if (obj.has("duration")) obj.optDouble("duration") else null,
                error = obj.optBoolean("error", false),
                preview = preview
            )
        } else {
            StreamEvent.ToolStarted(name, preview)
        }
    }

    private fun readError(conn: HttpURLConnection): String =
        runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty() }
            .getOrDefault("")

    private fun httpError(code: Int, body: String): String {
        val detail = runCatching {
            val o = JSONObject(body)
            val err = o.opt("error")
            when (err) {
                is JSONObject -> err.optString("message")
                is String -> err
                else -> o.optString("message")
            }
        }.getOrNull().orEmpty().ifBlank { body.trim().take(240) }
        val hint = when (code) {
            401, 403 -> " (check the API key)"
            404 -> " (check the base URL — /v1 is added automatically)"
            429 -> " (too many concurrent runs)"
            else -> ""
        }
        return "HTTP $code$hint${if (detail.isBlank()) "" else ": $detail"}"
    }

    private fun normalize(base: String): String {
        var b = base.trim().trimEnd('/')
        if (b.isBlank()) return ""
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "http://$b"
        // Tolerate a pasted full endpoint (…/v1/chat/completions) as well as a bare host.
        listOf("/chat/completions", "/models", "/health").forEach { suffix ->
            if (b.endsWith(suffix)) b = b.removeSuffix(suffix).trimEnd('/')
        }
        if (!b.endsWith("/v1")) b = "$b/v1"
        return b
    }

    fun chatUrl(base: String) = "${normalize(base)}/chat/completions"
    fun modelsUrl(base: String) = "${normalize(base)}/models"
    fun healthUrl(base: String) = "${normalize(base)}/health"

    companion object {
        /** The model alias the Hermes api_server answers to when the user has not picked one. */
        const val DEFAULT_MODEL = "hermes-agent"
    }
}
