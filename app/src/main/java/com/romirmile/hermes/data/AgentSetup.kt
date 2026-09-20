package com.romirmile.hermes.data

import android.content.Context
import java.util.Base64

/**
 * One-tap agent-side setup.
 *
 * A Hermes `api_server` gateway serves no audio routes at all (`/api/audio/…` is dashboard-only), so a
 * client cannot ask the agent to speak until the companion plugin is present on the agent's host — and
 * those routes only appear after a gateway restart. Rather than shipping a file for the user to copy
 * over by hand, the app asks the agent it is already talking to (which has file and shell tools) to
 * install it. The plugin source rides inside this app as assets, base64-encoded so the agent only has
 * to write each blob verbatim and decode it.
 *
 * The one step no program can take for the user is the gateway restart; the app surfaces that
 * command and then re-probes the endpoint.
 */
object AgentSetup {

    /** The single command left for the user to run on the agent's host. */
    const val RESTART_COMMAND = "hermes gateway restart"

    private const val ASSET_DIR = "hermes-audio-api"
    private const val PLUGIN_NAME = "hermes-audio-api"

    /** Files the agent has to write, as shipped in `assets/hermes-audio-api/`. */
    private val FILES = listOf("plugin.yaml", "__init__.py")

    /** True when this build actually carries the payload (never assume the asset is there). */
    fun isAvailable(context: Context): Boolean = FILES.all { name ->
        runCatching { context.assets.open("$ASSET_DIR/$name").use { it.readBytes() } }.isSuccess
    }

    /**
     * The self-contained instruction the agent receives: the plugin source inline, so the app never
     * depends on a URL or a download being reachable from the agent's host.
     */
    fun prompt(context: Context): String {
        val blocks = FILES.joinToString("\n\n") { name ->
            val encoded = Base64.getEncoder().encodeToString(
                context.assets.open("$ASSET_DIR/$name").use { it.readBytes() }
            )
            "PATH: ~/.hermes/plugins/$PLUGIN_NAME/$name\n$encoded"
        }
        return """
            Setup request from the Hermes phone app (the user tapped "Set up agent voice" in its
            Speech settings). Please do this on the machine you are running on.

            Why: this agent's gateway implements no /api/audio/* routes, so a client cannot ask the
            agent to synthesize speech. The plugin below adds them through the supported plugin hook
            (ctx.register_platform_handler) and lives in the plugins directory, so it survives
            `hermes update`.

            1. Make the directory and write each base64 blob below to its PATH, exactly as given —
               verbatim, decoded, no reformatting, no re-wrapping, no edits to the code:
                 mkdir -p ~/.hermes/plugins/$PLUGIN_NAME
                 # write the blob to a temp file (e.g. /tmp/plugin.yaml.b64), then:
                 base64 -d /tmp/plugin.yaml.b64 > ~/.hermes/plugins/$PLUGIN_NAME/plugin.yaml
                 base64 -d /tmp/__init__.py.b64 > ~/.hermes/plugins/$PLUGIN_NAME/__init__.py

            2. Enable it and confirm it loads:
                 hermes plugins enable $PLUGIN_NAME
                 hermes plugins doctor $PLUGIN_NAME

            3. Do NOT restart the gateway yourself: a gateway restart has to be run outside the
               gateway process, and the user will do it. Just say that it is required.

            Report back in a few short lines: whether the plugin is enabled, what doctor said, and
            anything that failed. If you run a named Hermes profile, use that profile's plugins
            directory instead of ~/.hermes/plugins.

            --- payload ---

            $blocks
        """.trimIndent()
    }
}
