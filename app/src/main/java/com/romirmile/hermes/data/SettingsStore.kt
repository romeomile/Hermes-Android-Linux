package com.romirmile.hermes.data

import android.content.Context
import java.util.UUID

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Where the spoken reply comes from: the phone's engine, the Hermes agent's own TTS setup
 * (the provider, voice and key configured on the agent — nothing to configure here), or a speech
 * provider the user configures in the app itself (their own endpoint and key), which needs no
 * agent-side support at all.
 */
enum class SpeechEngine { PHONE, AGENT, OWN }

data class AppSettings(
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String = "hermes-agent",
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val speechEngine: SpeechEngine = SpeechEngine.PHONE,
    val speechModel: String = "",
    val speechVoice: String = "",
    val ownSpeechUrl: String = "",
    val ownSpeechKey: String = "",
    val ownSpeechModel: String = "",
    val ownSpeechVoice: String = "",
    /** Engine voice for the phone engine ("" = follow the phone's own text-to-speech settings). */
    val phoneVoice: String = ""
)

/**
 * Persisted client configuration: gateway base URL, bearer key, model name, theme.
 * SharedPreferences is enough — the values are small and non-relational.
 */
object SettingsStore {

    private const val PREFS = "hermes_settings"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_THEME = "theme"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_SPEECH_ENGINE = "speech_engine"
    private const val KEY_SPEECH_MODEL = "speech_model"
    private const val KEY_SPEECH_VOICE = "speech_voice"
    private const val KEY_OWN_SPEECH_URL = "own_speech_url"
    private const val KEY_OWN_SPEECH_KEY = "own_speech_key"
    private const val KEY_OWN_SPEECH_MODEL = "own_speech_model"
    private const val KEY_OWN_SPEECH_VOICE = "own_speech_voice"
    private const val KEY_PHONE_VOICE = "phone_voice"

    /**
     * Loads settings, first upgrading anything still stored in the clear: credentials are encrypted
     * with [SecretStore], so a value written by an older build is rewritten once here, then read
     * normally.
     */
    fun loadAfterMigrating(context: Context): AppSettings {
        migrateSecrets(context)
        return load(context)
    }

    fun load(context: Context): AppSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val theme = runCatching {
            ThemeMode.valueOf(p.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
        val engine = runCatching {
            SpeechEngine.valueOf(
                p.getString(KEY_SPEECH_ENGINE, SpeechEngine.PHONE.name) ?: SpeechEngine.PHONE.name
            )
        }.getOrDefault(SpeechEngine.PHONE)
        return AppSettings(
            endpoint = p.getString(KEY_ENDPOINT, "") ?: "",
            apiKey = SecretStore.decrypt(p.getString(KEY_API_KEY, "") ?: ""),
            model = p.getString(KEY_MODEL, "hermes-agent") ?: "hermes-agent",
            theme = theme,
            speechEngine = engine,
            speechModel = p.getString(KEY_SPEECH_MODEL, "") ?: "",
            speechVoice = p.getString(KEY_SPEECH_VOICE, "") ?: "",
            ownSpeechUrl = p.getString(KEY_OWN_SPEECH_URL, "") ?: "",
            ownSpeechKey = SecretStore.decrypt(p.getString(KEY_OWN_SPEECH_KEY, "") ?: ""),
            ownSpeechModel = p.getString(KEY_OWN_SPEECH_MODEL, "") ?: "",
            ownSpeechVoice = p.getString(KEY_OWN_SPEECH_VOICE, "") ?: "",
            phoneVoice = p.getString(KEY_PHONE_VOICE, "") ?: ""
        )
    }

    fun save(context: Context, settings: AppSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENDPOINT, settings.endpoint)
            .putString(KEY_API_KEY, SecretStore.encrypt(settings.apiKey))
            .putString(KEY_MODEL, settings.model)
            .putString(KEY_THEME, settings.theme.name)
            .putString(KEY_SPEECH_ENGINE, settings.speechEngine.name)
            .putString(KEY_SPEECH_MODEL, settings.speechModel)
            .putString(KEY_SPEECH_VOICE, settings.speechVoice)
            .putString(KEY_OWN_SPEECH_URL, settings.ownSpeechUrl)
            .putString(KEY_OWN_SPEECH_KEY, SecretStore.encrypt(settings.ownSpeechKey))
            .putString(KEY_OWN_SPEECH_MODEL, settings.ownSpeechModel)
            .putString(KEY_OWN_SPEECH_VOICE, settings.ownSpeechVoice)
            .putString(KEY_PHONE_VOICE, settings.phoneVoice)
            .apply()
    }

    /** Rewrites any credential still stored in the clear as an encrypted value (one-off). */
    fun migrateSecrets(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        var changed = false
        for (key in listOf(KEY_API_KEY, KEY_OWN_SPEECH_KEY)) {
            val current = prefs.getString(key, "") ?: ""
            if (current.isNotEmpty() && !SecretStore.isEncrypted(current)) {
                edit.putString(key, SecretStore.encrypt(current))
                changed = true
            }
        }
        if (changed) edit.apply()
    }

    /**
     * Stable identifier for this install, used as the gateway's `X-Hermes-Session-Key` so long-term
     * memory follows the person across sessions (a new chat is a new session, but not a new user).
     */
    fun installId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, generated).apply()
        return generated
    }
}
