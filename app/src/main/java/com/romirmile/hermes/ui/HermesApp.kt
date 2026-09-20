package com.romirmile.hermes.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import com.romirmile.hermes.vm.EngineController

/** Which surface is on screen: the engine controls or the Hermes interface. */
private enum class Screen { ENGINE, INTERFACE }

/**
 * The app has exactly two surfaces.
 *
 * 1. The **engine screen** is the entry point: it starts the Linux VM, reports each boot step and
 *    holds the model setup.
 * 2. The **interface** is the WebView running the Hermes-mobile shell, which is served from the app's
 *    assets and backed by the dashboard the VM hosts.
 *
 * Once the engine reports the dashboard reachable on `127.0.0.1:9129`, the interface takes over.
 * System BACK inside the WebView walks its history first and comes back here when there is nothing
 * left to go back to.
 */
@Composable
fun HermesApp() {
    val context = LocalContext.current
    val activity = context as? Activity
    val clipboard = LocalClipboardManager.current
    var screen by rememberSaveable { mutableStateOf(Screen.ENGINE.name) }

    val engineState by EngineController.state.collectAsState()
    val engineLog by EngineController.log.collectAsState()

    LaunchedEffect(engineState.dashboardReady) {
        if (engineState.dashboardReady && screen == Screen.ENGINE.name) {
            screen = Screen.INTERFACE.name
        }
    }

    HermesTheme(ThemeMode.SYSTEM) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (screen == Screen.INTERFACE.name) {
                DashboardWebView(onExit = { screen = Screen.ENGINE.name })
            } else {
                EngineScreen(
                    state = engineState,
                    log = engineLog,
                    token = EngineController.token(context),
                    onBack = {
                        // Ready means the dashboard is serving, so back goes to the interface;
                        // otherwise there is nothing behind this screen and the app closes.
                        if (engineState.dashboardReady) {
                            screen = Screen.INTERFACE.name
                        } else {
                            activity?.finish()
                        }
                    },
                    onOpenInterface = { screen = Screen.INTERFACE.name },
                    onStart = { EngineController.start(context) },
                    onStop = { EngineController.stop(context) },
                    onRestartAgent = { EngineController.restartAgent(context) },
                    onRefresh = { EngineController.refresh(context) },
                    onApplyAgent = { provider, model, credentialName, credentialValue ->
                        EngineController.configureAgent(
                            context = context,
                            provider = provider,
                            model = model,
                            credentialName = credentialName,
                            credentialValue = credentialValue
                        ) { ok ->
                            EngineController.logLine(
                                if (ok) "the dashboard restarted with the new model"
                                else "the model change did not take effect"
                            )
                        }
                    },
                    onCopyLog = { text -> clipboard.setText(AnnotatedString(text)) },
                    onRunShell = { command, callback ->
                        EngineController.shell(context, command, timeoutSeconds = 300) { output ->
                            callback(output)
                        }
                    }
                )
                BackHandler { activity?.finish() }
            }
        }
    }
}
