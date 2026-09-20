package com.romirmile.hermes.data

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice

/**
 * The phone engine's installed voices, for the user to choose from.
 *
 * The app used to leave the engine on its own defaults and force `language = Locale.getDefault()` at
 * start-up, which silently reset a voice picked in the phone's text-to-speech settings — so changing
 * the voice looked like it had no effect. Listing the engine's voices and applying the chosen one
 * explicitly makes the choice real; "System default" (an empty name) leaves the engine alone, so the
 * phone's own settings rule.
 */
object PhoneVoice {

    /** One selectable engine voice; [label] is what the settings screen shows. */
    data class Option(val name: String, val label: String, val needsNetwork: Boolean)

    /** Installed voices, highest quality first. Empty until the engine has finished initialising. */
    fun options(tts: TextToSpeech): List<Option> = runCatching {
        tts.voices.orEmpty()
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.locale.toLanguageTag() })
            .map { Option(it.name, label(it), it.isNetworkConnectionRequired) }
    }.getOrDefault(emptyList())

    private fun label(voice: Voice): String {
        val quality = when (voice.quality) {
            Voice.QUALITY_VERY_HIGH -> "very high"
            Voice.QUALITY_HIGH -> "high"
            Voice.QUALITY_NORMAL -> "normal"
            Voice.QUALITY_LOW -> "low"
            else -> "unknown"
        }
        val tag = runCatching { voice.locale.toLanguageTag() }.getOrDefault("")
        val network = if (voice.isNetworkConnectionRequired) ", network" else ""
        return "$tag — ${voice.name} ($quality$network)"
    }

    /**
     * Makes [name] the engine's voice (and its locale). An empty [name] means "system default" and
     * touches nothing. Returns false when the stored voice is no longer installed — the caller can
     * then keep the engine default instead of going silent.
     */
    fun apply(tts: TextToSpeech, name: String): Boolean {
        if (name.isBlank()) return true
        val voice = runCatching {
            tts.voices.orEmpty().firstOrNull { it.name == name }
        }.getOrNull() ?: return false
        return runCatching { tts.voice = voice }
            .getOrDefault(TextToSpeech.ERROR) != TextToSpeech.ERROR
    }

    /** True when the stored choice is currently installed (or when it is the system default). */
    fun isInstalled(tts: TextToSpeech, name: String): Boolean =
        name.isBlank() || runCatching {
            tts.voices.orEmpty().any { it.name == name }
        }.getOrDefault(false)
}
