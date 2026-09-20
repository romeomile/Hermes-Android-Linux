package com.romirmile.hermes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import android.speech.tts.TextToSpeech
import android.widget.Toast
import com.romirmile.hermes.R
import com.romirmile.hermes.data.AgentSetup
import com.romirmile.hermes.data.PhoneVoice
import com.romirmile.hermes.data.SpeechCatalogue
import com.romirmile.hermes.data.SpeechEngine
import com.romirmile.hermes.data.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: HermesViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val notice by vm.notice.collectAsState()
    val models by vm.models.collectAsState()

    var endpoint by remember { mutableStateOf(settings.endpoint) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }
    var showKey by remember { mutableStateOf(false) }
    var themeExpanded by remember { mutableStateOf(false) }
    var engineExpanded by remember { mutableStateOf(false) }
    var speechModelExpanded by remember { mutableStateOf(false) }
    var speechModel by remember { mutableStateOf(settings.speechModel) }
    var speechVoice by remember { mutableStateOf(settings.speechVoice) }
    val voiceStatus by vm.voiceStatus.collectAsState()
    val speechModels by vm.speechModels.collectAsState()
    val agentSetupStatus by vm.agentSetupStatus.collectAsState()
    val agentSetupRunning by vm.agentSetupRunning.collectAsState()
    var ownSpeechUrl by remember { mutableStateOf(settings.ownSpeechUrl) }
    var ownSpeechKey by remember { mutableStateOf(settings.ownSpeechKey) }
    var ownSpeechModel by remember { mutableStateOf(settings.ownSpeechModel) }
    var ownSpeechVoice by remember { mutableStateOf(settings.ownSpeechVoice) }
    var showOwnKey by remember { mutableStateOf(false) }
    var phoneVoiceExpanded by remember { mutableStateOf(false) }
    var phoneVoices by remember { mutableStateOf<List<PhoneVoice.Option>>(emptyList()) }
    var voiceEngineReady by remember { mutableStateOf(false) }
    // A second engine instance, only to list and preview the phone's voices (the call screen owns
    // its own). Reading the list explicitly is what makes the choice visible.
    val voiceEngine = remember {
        TextToSpeech(context) { status -> voiceEngineReady = status == TextToSpeech.SUCCESS }
    }
    DisposableEffect(voiceEngine) {
        onDispose { runCatching { voiceEngine.shutdown() } }
    }
    LaunchedEffect(voiceEngineReady, settings.speechEngine) {
        if (voiceEngineReady) phoneVoices = PhoneVoice.options(voiceEngine)
    }

    // Ask the agent what it can do for speech as soon as agent voice is selected.
    LaunchedEffect(settings.speechEngine, settings.endpoint, settings.apiKey) {
        if (settings.speechEngine == SpeechEngine.AGENT) vm.refreshVoice()
    }

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            SectionLabel(stringResource(R.string.settings_section_connection))
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_endpoint_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = {
                    Text(
                        stringResource(R.string.settings_endpoint_support),
                        fontSize = 11.sp
                    )
                }
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_key_label)) },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            stringResource(
                                if (showKey) R.string.settings_hide_key else R.string.settings_show_key
                            )
                        )
                    }
                }
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_model_label)) },
                singleLine = true,
                supportingText = {
                    Text(stringResource(R.string.settings_model_support), fontSize = 11.sp)
                }
            )
            if (models.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyRow {
                    items(models) { id ->
                        FilterChip(
                            selected = id == model,
                            onClick = { model = id },
                            label = { Text(id, fontSize = 12.sp) },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { vm.fetchModels(endpoint, apiKey) }) {
                    Text(stringResource(R.string.settings_fetch_models), fontSize = 13.sp)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.testConnection(endpoint, apiKey) }) {
                    Text(stringResource(R.string.settings_test_connection), fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { vm.saveConnection(endpoint, apiKey, model) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_save_connection))
            }
            val status = notice
            if (status != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    status,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.settings_section_appearance))
            ExposedDropdownMenuBox(
                expanded = themeExpanded,
                onExpandedChange = { themeExpanded = it }
            ) {
                OutlinedTextField(
                    value = themeLabel(settings.theme),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.settings_theme_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = themeExpanded)
                    },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = themeExpanded,
                    onDismissRequest = { themeExpanded = false }
                ) {
                    ThemeMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(themeLabel(mode)) },
                            onClick = {
                                vm.updateSettings { it.copy(theme = mode) }
                                themeExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.settings_section_speech))
            ExposedDropdownMenuBox(
                expanded = engineExpanded,
                onExpandedChange = { engineExpanded = it }
            ) {
                OutlinedTextField(
                    value = engineLabel(settings.speechEngine),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.settings_speech_engine_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = engineExpanded)
                    },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = engineExpanded,
                    onDismissRequest = { engineExpanded = false }
                ) {
                    SpeechEngine.entries.forEach { engine ->
                        DropdownMenuItem(
                            text = { Text(engineLabel(engine)) },
                            onClick = {
                                vm.updateSettings { it.copy(speechEngine = engine) }
                                engineExpanded = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_speech_engine_support),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (settings.speechEngine == SpeechEngine.AGENT) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { vm.refreshVoice() }) {
                    Text(stringResource(R.string.settings_voice_check), fontSize = 13.sp)
                }
                val status = voiceStatus
                if (status != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        status,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // One-tap: the agent installs its own speech endpoints — no file to copy by hand.
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.settings_agent_setup_support),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.installAgentVoice() },
                    enabled = !agentSetupRunning
                ) {
                    Text(stringResource(R.string.settings_agent_setup_button), fontSize = 13.sp)
                }
                val setupStatus = agentSetupStatus
                if (setupStatus != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        setupStatus,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.settings_agent_setup_restart),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            AgentSetup.RESTART_COMMAND,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                ClipData.newPlainText("command", AgentSetup.RESTART_COMMAND)
                            )
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_agent_setup_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        }) {
                            Text(stringResource(R.string.settings_agent_setup_copy), fontSize = 12.sp)
                        }
                    }
                }

                if (speechModels.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    ExposedDropdownMenuBox(
                        expanded = speechModelExpanded,
                        onExpandedChange = { speechModelExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = speechModels.firstOrNull { it.id == speechModel }
                                ?.let { speechModelLabel(it) } ?: speechModel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.settings_speech_model_label)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(
                                    expanded = speechModelExpanded
                                )
                            },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = speechModelExpanded,
                            onDismissRequest = { speechModelExpanded = false }
                        ) {
                            speechModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(speechModelLabel(model), fontSize = 13.sp) },
                                    onClick = {
                                        speechModel = model.id
                                        speechVoice = ""
                                        vm.updateSettings {
                                            it.copy(speechModel = model.id, speechVoice = "")
                                        }
                                        speechModelExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = speechModel,
                    onValueChange = {
                        speechModel = it
                        vm.updateSettings { current -> current.copy(speechModel = it) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_speech_model_label)) },
                    singleLine = true,
                    supportingText = {
                        Text(stringResource(R.string.settings_speech_model_support), fontSize = 11.sp)
                    }
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = speechVoice,
                    onValueChange = {
                        speechVoice = it
                        vm.updateSettings { current -> current.copy(speechVoice = it) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_speech_voice_label)) },
                    singleLine = true,
                    supportingText = {
                        Text(stringResource(R.string.settings_speech_voice_support), fontSize = 11.sp)
                    }
                )
            }

            if (settings.speechEngine == SpeechEngine.PHONE) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.settings_phone_voice_support),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                ExposedDropdownMenuBox(
                    expanded = phoneVoiceExpanded,
                    onExpandedChange = { phoneVoiceExpanded = it }
                ) {
                    OutlinedTextField(
                        value = phoneVoices.firstOrNull { it.name == settings.phoneVoice }?.label
                            ?: stringResource(R.string.settings_phone_voice_system),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.settings_phone_voice_label)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = phoneVoiceExpanded)
                        },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = phoneVoiceExpanded,
                        onDismissRequest = { phoneVoiceExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.settings_phone_voice_system),
                                    fontSize = 13.sp
                                )
                            },
                            onClick = {
                                vm.updateSettings { current -> current.copy(phoneVoice = "") }
                                phoneVoiceExpanded = false
                            }
                        )
                        phoneVoices.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label, fontSize = 12.sp) },
                                onClick = {
                                    vm.updateSettings { current ->
                                        current.copy(phoneVoice = option.name)
                                    }
                                    phoneVoiceExpanded = false
                                }
                            )
                        }
                    }
                }
                if (phoneVoices.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.settings_phone_voice_none),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                val testLine = stringResource(R.string.settings_phone_voice_test_line)
                Row(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { phoneVoices = PhoneVoice.options(voiceEngine) }) {
                        Text(stringResource(R.string.settings_phone_voice_refresh), fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        PhoneVoice.apply(voiceEngine, settings.phoneVoice)
                        voiceEngine.speak(
                            testLine,
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "hermes-voice-preview"
                        )
                    }) {
                        Text(stringResource(R.string.settings_phone_voice_test), fontSize = 13.sp)
                    }
                }
            }

            if (settings.speechEngine == SpeechEngine.OWN) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.settings_own_speech_support),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = ownSpeechUrl,
                    onValueChange = {
                        ownSpeechUrl = it
                        vm.updateSettings { current -> current.copy(ownSpeechUrl = it) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_own_speech_url_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    supportingText = {
                        Text(
                            stringResource(R.string.settings_own_speech_url_support),
                            fontSize = 11.sp
                        )
                    }
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = ownSpeechKey,
                    onValueChange = {
                        ownSpeechKey = it
                        vm.updateSettings { current -> current.copy(ownSpeechKey = it) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_own_speech_key_label)) },
                    singleLine = true,
                    visualTransformation = if (showOwnKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showOwnKey = !showOwnKey }) {
                            Icon(
                                if (showOwnKey) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                stringResource(
                                    if (showOwnKey) R.string.settings_hide_key
                                    else R.string.settings_show_key
                                )
                            )
                        }
                    }
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = ownSpeechModel,
                    onValueChange = {
                        ownSpeechModel = it
                        vm.updateSettings { current -> current.copy(ownSpeechModel = it) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_own_speech_model_label)) },
                    singleLine = true,
                    supportingText = {
                        Text(
                            stringResource(R.string.settings_own_speech_model_support),
                            fontSize = 11.sp
                        )
                    }
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = ownSpeechVoice,
                    onValueChange = {
                        ownSpeechVoice = it
                        vm.updateSettings { current -> current.copy(ownSpeechVoice = it) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_own_speech_voice_label)) },
                    singleLine = true,
                    supportingText = {
                        Text(
                            stringResource(R.string.settings_own_speech_voice_support),
                            fontSize = 11.sp
                        )
                    }
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.settings_section_about))
            Text(
                stringResource(R.string.settings_about_text),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.settings_version, versionName),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 10.dp)
    )
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }
)

@Composable
private fun engineLabel(engine: SpeechEngine): String = stringResource(
    when (engine) {
        SpeechEngine.PHONE -> R.string.speech_engine_phone
        SpeechEngine.AGENT -> R.string.speech_engine_agent
        SpeechEngine.OWN -> R.string.speech_engine_own
    }
)

/** "Deepgram Flux TTS (deepgram/flux-tts:free) — Free" or "… — $0.015 per 1k characters". */
@Composable
private fun speechModelLabel(model: SpeechCatalogue.SpeechModel): String {
    val price = if (model.free) {
        stringResource(R.string.speech_free_badge)
    } else {
        "$${"%.3f".format(model.pricePer1kChars)} / 1k chars"
    }
    return "${model.name} (${model.id}) — $price"
}
