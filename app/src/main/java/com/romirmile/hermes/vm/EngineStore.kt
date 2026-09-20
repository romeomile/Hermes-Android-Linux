package com.romirmile.hermes.vm

import android.content.Context
import java.util.UUID

/**
 * Persisted engine settings: how much of the phone the VM may use, whether it starts with the app,
 * and the token that guards the guest's control API.
 *
 * The token is generated on this device on first use and is handed to the guest through the kernel
 * command line at every boot. It is also the bearer key the agent's API server expects, so one
 * value covers both, and nothing about it is baked into the app or the guest image.
 */
class EngineStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var cpuCount: Int
        get() = prefs.getInt(KEY_CPU, DEFAULT_CPU).coerceIn(1, 4)
        set(value) = prefs.edit().putInt(KEY_CPU, value.coerceIn(1, 4)).apply()

    var ramMb: Int
        get() = prefs.getInt(KEY_RAM, DEFAULT_RAM_MB).coerceIn(512, 4096)
        set(value) = prefs.edit().putInt(KEY_RAM, value.coerceIn(512, 4096)).apply()

    /** When true the app brings the VM up on launch, so chat is ready without opening this screen. */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTOSTART, value).apply()

    val token: String
        get() = prefs.getString(KEY_TOKEN, null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_TOKEN, it).apply() }

    /** Provider/model/key the user configured for the agent, kept only as a UI default. */
    var provider: String
        get() = prefs.getString(KEY_PROVIDER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROVIDER, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    companion object {
        private const val PREFS = "hermes_engine"
        private const val KEY_CPU = "cpu_count"
        private const val KEY_RAM = "ram_mb"
        private const val KEY_AUTOSTART = "auto_start"
        private const val KEY_TOKEN = "api_token"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "model"

        const val DEFAULT_CPU = 2
        const val DEFAULT_RAM_MB = 2048

        /** Control API inside the guest (QEMU hostfwd: device 127.0.0.1 -> guest). */
        const val CONTROL_PORT = 7080

        /**
         * The Hermes dashboard, reached through the in-guest relay that listens on the guest's
         * `0.0.0.0:9129`. QEMU forwards the device's `127.0.0.1:9129` to it, so the WebView and the
         * native dashboard proxy both address loopback on this port — the same port the mobile shell
         * was built against.
         *
         * The dashboard itself stays on the guest's loopback (`127.0.0.1:9128`); binding it to a
         * non-loopback address would make it serve a login page instead of the app.
         */
        const val DASHBOARD_PORT = 9129

        /** Loopback address of the dashboard as seen from this device. */
        fun dashboardUrl(): String = "http://127.0.0.1:$DASHBOARD_PORT"

        /**
         * Public provider -> credential variable names, so the setup screen can fill the right one
         * in. These are upstream provider conventions, not values taken from anywhere.
         */
        val PROVIDER_KEYS: List<Pair<String, String>> = listOf(
            "deepseek" to "DEEPSEEK_API_KEY",
            "openai" to "OPENAI_API_KEY",
            "anthropic" to "ANTHROPIC_API_KEY",
            "openrouter" to "OPENROUTER_API_KEY",
            "gemini" to "GEMINI_API_KEY",
            "xai" to "XAI_API_KEY",
            "mistral" to "MISTRAL_API_KEY",
            "groq" to "GROQ_API_KEY"
        )

        fun credentialNameFor(provider: String): String =
            PROVIDER_KEYS.firstOrNull { it.first.equals(provider.trim(), ignoreCase = true) }?.second
                ?: "${provider.trim().uppercase().replace('-', '_')}_API_KEY"
    }
}
