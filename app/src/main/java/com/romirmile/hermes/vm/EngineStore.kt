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

    /**
     * Size of the guest's writable disk. It is a sparse qcow2, so a 20 GB disk occupies only what the
     * guest actually writes (a fresh guest uses ~150 MB). It is grown in place on the next boot when
     * raised, and never shrunk — shrinking would mean rebuilding the disk and losing the guest.
     */
    var diskGb: Int
        get() = prefs.getInt(KEY_DISK, DEFAULT_DISK_GB).coerceIn(MIN_DISK_GB, MAX_DISK_GB)
        set(value) = prefs.edit().putInt(KEY_DISK, value.coerceIn(MIN_DISK_GB, MAX_DISK_GB)).apply()

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
        private const val KEY_DISK = "disk_gb"
        private const val KEY_AUTOSTART = "auto_start"
        private const val KEY_TOKEN = "api_token"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "model"

        const val DEFAULT_CPU = 2
        const val DEFAULT_RAM_MB = 2048

        /** Guest disk sizes offered in the UI; the guest lists the space as usable GB. */
        val DISK_CHOICES_GB = listOf(20, 32, 64)
        const val DEFAULT_DISK_GB = 20
        const val MIN_DISK_GB = 10
        const val MAX_DISK_GB = 64

        /**
         * Ports *inside the guest*, where its own services listen. This is the guest's contract, it
         * never changes with the app, and it is what a command run inside the guest must address.
         */
        const val GUEST_CONTROL_PORT = 7080
        const val GUEST_AGENT_PORT = 8642

        /**
         * Device-side ports the guest's services are forwarded onto (QEMU `hostfwd`), and the only
         * ones the app itself may use.
         *
         * Deliberately NOT the guest's own numbers. The other on-device app's guest binds 7080/8642
         * too, and two QEMU forwards cannot share a device port: the second guest fails to bind its
         * forwards and exits, while the app keeps talking to the *other* installation's guest, whose
         * token it does not accept. On screen that reads as the engine reporting "stopped" while the
         * control API answers and rejects the request. The relay front end solved the same collision
         * the same way (17080/18642/19129), so both apps can run side by side.
         */
        const val CONTROL_PORT = 17080
        const val AGENT_PORT = 18642

        /** Loopback address a chat frontend on this device can reach the agent on. */
        fun localEndpoint(): String = "http://127.0.0.1:$AGENT_PORT"

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
