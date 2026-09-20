package com.romirmile.hermes.data

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Speech through the Hermes agent itself.
 *
 * `POST /api/audio/speak` runs the agent's own TTS provider chain — the profile's configured
 * provider, model, voice and credential — so whichever speech provider the user set up on their
 * agent does the talking, and no speech credential is stored here.
 * the only secret sent is the gateway API key the app already uses for chat.
 *
 * Available on Hermes builds that expose the audio API (`audio_api` in `/v1/capabilities`); the
 * caller falls back to the phone's own engine when it isn't.
 */
object HermesVoice {

    /**
     * Where the agent's audio API may live: the dashboard path first, then the OpenAI-style `/v1`
     * mirror the companion plugin also serves (a reverse proxy may expose only `/v1`).
     */
    private val AUDIO_PREFIXES = listOf("/api/audio", "/v1/audio")

    /** Speech could not be produced here (endpoint missing, provider failure, bad payload). */
    class VoiceException(message: String) : Exception(message)

    /**
     * Audio bytes straight from the endpoint the agent resolved for us, with the model and voice the
     * app selected. The credential is the one the agent handed over for this session; it is never
     * persisted. Models that only emit raw pcm are wrapped into a WAV container.
     */
    fun synthesizeDirect(
        baseUrl: String,
        apiKey: String,
        model: String,
        voice: String?,
        text: String
    ): ByteArray {
        val speechUrl = "${baseUrl.trim().trimEnd('/')}/audio/speech"
        val mp3 = runCatching { postSpeech(speechUrl, apiKey, model, voice, text, "mp3") }
        mp3.getOrNull()?.let { return it }
        val failure = mp3.exceptionOrNull()
        val detail = failure?.message.orEmpty()
        if (failure !is VoiceException || !detail.contains("pcm", ignoreCase = true)) {
            throw failure ?: VoiceException("Speech request failed")
        }
        val pcm = postSpeech(speechUrl, apiKey, model, voice, text, "pcm")
        return wrapPcmAsWav(pcm, 24_000)
    }

    private fun postSpeech(
        url: String,
        apiKey: String,
        model: String,
        voice: String?,
        text: String,
        format: String
    ): ByteArray {
        val payload = JSONObject().apply {
            put("model", model)
            put("input", text)
            put("response_format", format)
            if (!voice.isNullOrBlank()) put("voice", voice)
        }.toString()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw VoiceException("HTTP $code" + if (detail.isBlank()) "" else ": ${detail.take(200)}")
            }
            val audio = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                input.copyTo(out)
                out.toByteArray()
            }
            if (audio.isEmpty()) throw VoiceException("Empty audio response")
            return audio
        } finally {
            conn.disconnect()
        }
    }

    /** 44-byte RIFF/WAVE header in front of 16-bit mono pcm. */
    private fun wrapPcmAsWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val header = ByteArray(44)

        fun ascii(offset: Int, value: String) {
            value.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        }

        fun int(offset: Int, value: Int) {
            for (i in 0 until 4) header[offset + i] = (value shr (8 * i) and 0xff).toByte()
        }

        fun short(offset: Int, value: Int) {
            for (i in 0 until 2) header[offset + i] = (value shr (8 * i) and 0xff).toByte()
        }

        ascii(0, "RIFF")
        int(4, 36 + pcm.size)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        int(16, 16)
        short(20, 1) // PCM
        short(22, channels)
        int(24, sampleRate)
        int(28, sampleRate * channels * bitsPerSample / 8)
        short(32, channels * bitsPerSample / 8)
        short(34, bitsPerSample)
        ascii(36, "data")
        int(40, pcm.size)
        return header + pcm
    }

    /**
     * Audio bytes for [text], as returned by the agent (`data_url` payload, mp3/ogg/wav depending on
     * the provider). [baseUrl] is the same gateway URL used for chat.
     */
    fun speak(baseUrl: String, apiKey: String, text: String): ByteArray {
        val root = baseUrl.trim().trimEnd('/')
            .removeSuffix("/v1")
        return speakVia(root, apiKey, text)
    }

    /**
     * POSTs to the first audio prefix that answers. Only a 404 sends us to the next candidate — any
     * other failure (auth, provider error) is the real answer and is reported as-is.
     */
    private fun speakVia(root: String, apiKey: String, text: String): ByteArray {
        var last: VoiceException? = null
        for (prefix in AUDIO_PREFIXES) {
            try {
                return postSpeak("$root$prefix/speak", apiKey, text)
            } catch (e: VoiceException) {
                last = e
                if (!e.message.orEmpty().contains("HTTP 404")) throw e
            }
        }
        throw last ?: VoiceException("Speech request failed")
    }

    private fun postSpeak(url: String, apiKey: String, text: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            val payload = JSONObject().put("text", text).toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw VoiceException("HTTP $code" + if (detail.isBlank()) "" else ": ${detail.take(200)}")
            }
            val body = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                input.copyTo(out)
                out.toString(Charsets.UTF_8.name())
            }
            val dataUrl = JSONObject(body).optString("data_url")
            if (!dataUrl.startsWith("data:") || "," !in dataUrl) {
                throw VoiceException("Empty audio response")
            }
            val encoded = dataUrl.substringAfter(',')
            return runCatching { Base64.getDecoder().decode(encoded) }.getOrElse {
                throw VoiceException("Audio payload is not valid base64")
            }
        } catch (t: javax.net.ssl.SSLException) {
            throw VoiceException("Connection failed: ${t.message}")
        } finally {
            conn.disconnect()
        }
    }
}
