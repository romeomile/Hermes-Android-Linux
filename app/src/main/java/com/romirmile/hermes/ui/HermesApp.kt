package com.romirmile.hermes.ui

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
import com.romirmile.hermes.data.SpeechEngine
import com.romirmile.hermes.vm.AgentState
import com.romirmile.hermes.vm.EngineController
import com.romirmile.hermes.vm.EngineStore

/** Which full-screen surface is on top of the chat. */
private enum class Screen { CHAT, SETTINGS, VOICE, JOBS, SKILLS, MODELS, ENGINE }

@Composable
fun HermesApp(vm: HermesViewModel) {
    val settings by vm.settings.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.CHAT.name) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val engineState by EngineController.state.collectAsState()
    val engineLog by EngineController.log.collectAsState()

    fun open(target: Screen) {
        // Loading on open keeps the pages honest: they show what the agent reports right now.
        when (target) {
            Screen.JOBS -> vm.loadJobs()
            Screen.SKILLS -> vm.loadSkills()
            Screen.MODELS -> vm.fetchModels(settings.endpoint, settings.apiKey)
            else -> Unit
        }
        screen = target.name
    }

    // The engine runs the gateway on this device, so a fresh install that has no endpoint of its own
    // is pointed at it the moment the agent is ready — chat works without any setup.
    LaunchedEffect(engineState.agent, settings.endpoint) {
        if (engineState.agent == AgentState.READY && settings.endpoint.isBlank()) {
            vm.updateSettings {
                it.copy(
                    endpoint = EngineStore.localEndpoint(),
                    apiKey = EngineController.token(context)
                )
            }
        }
    }

    HermesTheme(settings.theme) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ChatScreen(
                vm = vm,
                onOpenSettings = { open(Screen.SETTINGS) },
                onOpenVoice = { screen = Screen.VOICE.name },
                onOpenJobs = { open(Screen.JOBS) },
                onOpenSkills = { open(Screen.SKILLS) },
                onOpenModels = { open(Screen.MODELS) },
                onOpenEngine = { open(Screen.ENGINE) }
            )
            if (screen == Screen.SETTINGS.name) {
                SettingsScreen(vm = vm, onBack = { screen = Screen.CHAT.name })
                BackHandler { screen = Screen.CHAT.name }
            }
            if (screen == Screen.VOICE.name) {
                VoiceScreen(vm = vm, onClose = { screen = Screen.CHAT.name })
                BackHandler { screen = Screen.CHAT.name }
            }
            if (screen == Screen.JOBS.name) {
                JobsScreen(vm = vm, onBack = { screen = Screen.CHAT.name })
                BackHandler { screen = Screen.CHAT.name }
            }
            if (screen == Screen.SKILLS.name) {
                SkillsScreen(vm = vm, onBack = { screen = Screen.CHAT.name })
                BackHandler { screen = Screen.CHAT.name }
            }
            if (screen == Screen.MODELS.name) {
                ModelsScreen(vm = vm, onBack = { screen = Screen.CHAT.name })
                BackHandler { screen = Screen.CHAT.name }
            }
            if (screen == Screen.ENGINE.name) {
                EngineScreen(
                    state = engineState,
                    log = engineLog,
                    token = EngineController.token(context),
                    onBack = { screen = Screen.CHAT.name },
                    onStart = { EngineController.start(context) },
                    onStop = { EngineController.stop(context) },
                    onRestartAgent = { EngineController.restartAgent(context) },
                    onRefresh = { EngineController.refresh(context) },
                    onUseAsGateway = {
                        vm.updateSettings {
                            it.copy(
                                endpoint = EngineStore.localEndpoint(),
                                apiKey = EngineController.token(context)
                            )
                        }
                        EngineController.logLine("gateway address applied to the app settings")
                    },
                    onApplyAgent = { provider, model, credentialName, credentialValue ->
                        EngineController.configureAgent(
                            context = context,
                            provider = provider,
                            model = model,
                            credentialName = credentialName,
                            credentialValue = credentialValue
                        ) { ok ->
                            EngineController.logLine(
                                if (ok) "agent restarted with the new model"
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
                BackHandler { screen = Screen.CHAT.name }
            }
        }
    }
}
