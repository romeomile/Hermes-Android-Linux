package com.romirmile.hermes.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the user's agent will hand a client for speech.
 *
 * `GET /api/audio/voice-config` resolves the agent's own TTS setup. When it can be driven from a
 * client it answers `mode = "direct"` with the wire protocol, base URL, credential, model and voice
 * (OpenAI-compatible endpoints are covered, including a custom `base_url`); when the provider can
 * only run on the agent's host it answers `mode = "relay"`, and the client uses `/api/audio/speak`
 * instead. Nothing here is stored by the app.
 */
data class AgentTtsConfig(
    val mode: String,
    val wire: String? = null,
    val provider: String? = null,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val model: String? = null,
    val voice: String? = null,
    val speed: Double? = null,
    val reason: String? = null
) {
    /** The agent let us call its provider directly, so the app can pick the model itself. */
    val isDirect: Boolean
        get() = mode == "direct" && !baseUrl.isNullOrBlank() && !apiKey.isNullOrBlank()

    /** OpenAI-compatible speech wire — the shape OpenRouter's TTS endpoint also speaks. */
    val isOpenAiWire: Boolean
        get() = isDirect && wire == "openai-speech"

    /** The resolved endpoint is OpenRouter, so OpenRouter's speech catalogue applies. */
    val isOpenRouter: Boolean
        get() = baseUrl?.contains("openrouter.ai", ignoreCase = true) == true
}

object VoiceConfigClient {

    /**
     * Where the agent's audio API may live: the dashboard path first, then the OpenAI-style `/v1`
     * mirror the companion plugin also serves — a reverse proxy may expose only `/v1` to clients.
     */
    private val AUDIO_PREFIXES = listOf("/api/audio", "/v1/audio")

    /** Reads the agent's resolved voice config; throws with the HTTP detail for the status line. */
    fun fetch(baseUrl: String, apiKey: String): AgentTtsConfig {
        val base = root(baseUrl)
        val tried = mutableListOf<String>()
        var body: String? = null
        for (prefix in AUDIO_PREFIXES) {
            val path = "$prefix/voice-config"
            val attempt = runCatching { get("$base$path", apiKey, path) }
            if (attempt.isSuccess) {
                body = attempt.getOrThrow()
                break
            }
            tried.add(attempt.exceptionOrNull()?.message ?: "HTTP request failed")
        }
        val payload = body ?: throw IllegalStateException(tried.joinToString(" and "))
        val tts = JSONObject(payload).optJSONObject("tts") ?: return AgentTtsConfig(mode = "none")
        return AgentTtsConfig(
            mode = tts.optString("mode", "relay"),
            wire = tts.optString("wire").ifBlank { null },
            provider = tts.optString("provider").ifBlank { null },
            baseUrl = tts.optString("base_url").ifBlank { null },
            apiKey = tts.optString("api_key").ifBlank { null },
            model = tts.optString("model").ifBlank { null },
            voice = tts.optString("voice").ifBlank { null },
            speed = if (tts.has("speed")) tts.optDouble("speed") else null,
            reason = tts.optString("reason").ifBlank { null }
        )
    }

    private fun root(baseUrl: String): String =
        baseUrl.trim().trimEnd('/').removeSuffix("/v1")

    private fun get(url: String, apiKey: String, path: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code at $path")
            return body
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Speech models offered by the resolved endpoint, read from OpenRouter's public catalogue — no key
 * needed, so the app can verify which models are actually free instead of trusting a name.
 */
object SpeechCatalogue {

    data class SpeechModel(
        val id: String,
        val name: String,
        val free: Boolean,
        val pricePer1kChars: Double,
        val voices: List<String>
    )

    private const val OPENROUTER_SPEECH_MODELS =
        "https://openrouter.ai/api/v1/models?output_modalities=speech"

    /** Speech models, free ones first. Throws on network/parse failure. */
    fun openRouter(): List<SpeechModel> {
        val conn = (URL(OPENROUTER_SPEECH_MODELS).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
        }
        val body = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val pricing = item.optJSONObject("pricing")
            val perChar = pricing?.optString("prompt")?.toDoubleOrNull() ?: 0.0
            val outPerChar = pricing?.optString("completion")?.toDoubleOrNull() ?: 0.0
            // Free means the endpoint bills nothing at all, in either direction.
            val free = perChar <= 0.0 && outPerChar <= 0.0
            SpeechModel(
                id = id,
                name = item.optString("name").ifBlank { id },
                free = free,
                pricePer1kChars = perChar * 1000.0,
                voices = voicesOf(item)
            )
        }.sortedWith(compareByDescending<SpeechModel> { it.free }.thenBy { it.pricePer1kChars })
    }

    private fun voicesOf(item: JSONObject): List<String> {
        val voices = item.optJSONArray("supported_voices") ?: return emptyList()
        return (0 until voices.length()).mapNotNull { i ->
            when (val voice = voices.opt(i)) {
                is String -> voice
                is JSONObject -> voice.optString("id").ifBlank { voice.optString("voice_id") }
                else -> null
            }?.takeIf { it.isNotBlank() }
        }
    }
}
