package com.romirmile.hermes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.romirmile.hermes.R
import com.romirmile.hermes.vm.AgentState
import com.romirmile.hermes.vm.EngineState
import com.romirmile.hermes.vm.EngineStore

/**
 * The engine screen: the Linux VM that hosts the Hermes dashboard, the address that dashboard is
 * reached on, the resources the VM may take, the model setup, and the live log.
 *
 * Everything here is about the local machine, so it states the current state instead of explaining
 * it, and every long operation reports the step it is on.
 */
@Composable
fun EngineScreen(
    state: EngineState,
    log: List<String>,
    token: String,
    onBack: () -> Unit,
    onOpenInterface: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestartAgent: () -> Unit,
    onRefresh: () -> Unit,
    onApplyAgent: (provider: String, model: String, credentialName: String, credentialValue: String) -> Unit,
    onCopyLog: (String) -> Unit,
    onRunShell: (String, (String) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val store = remember { EngineStore(context) }
    val clipboard = LocalClipboardManager.current

    var cpu by remember { mutableStateOf(store.cpuCount) }
    var ram by remember { mutableStateOf(store.ramMb) }
    var autoStart by remember { mutableStateOf(store.autoStart) }
    var showKey by remember { mutableStateOf(false) }
    var provider by remember {
        mutableStateOf(store.provider.ifBlank { EngineStore.PROVIDER_KEYS.first().first })
    }
    var model by remember { mutableStateOf(store.model) }
    var credentialName by remember { mutableStateOf(EngineStore.credentialNameFor(provider)) }
    var credentialValue by remember { mutableStateOf("") }
    var shellCommand by remember { mutableStateOf("") }
    var shellOutput by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { onRefresh() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScreenHeader(stringResource(R.string.engine_title), onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            EngineStatusCard(
                state = state,
                onStart = onStart,
                onStop = onStop,
                onRestartAgent = onRestartAgent,
                onRefresh = onRefresh
            )

            SectionCard(stringResource(R.string.engine_gateway_title)) {
                Text(
                    stringResource(R.string.engine_gateway_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                InfoRow(stringResource(R.string.engine_endpoint), EngineStore.dashboardUrl(), clipboard)
                InfoRow(
                    label = stringResource(R.string.engine_key),
                    value = if (showKey) token else "•".repeat(16),
                    clipboard = if (showKey) clipboard else null
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(stringResource(if (showKey) R.string.engine_hide else R.string.engine_show))
                    }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(token))
                        notice = context.getString(R.string.engine_copied)
                    }) {
                        Text(stringResource(R.string.engine_copy_key))
                    }
                    TextButton(
                        onClick = onOpenInterface,
                        enabled = state.dashboardReady
                    ) {
                        Text(stringResource(R.string.engine_open_interface))
                    }
                }
                if (notice.isNotBlank()) {
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            SectionCard(stringResource(R.string.engine_device_title)) {
                EngineDropdown(
                    label = stringResource(R.string.engine_cpu),
                    options = listOf(1, 2, 3, 4),
                    selected = cpu,
                    optionLabel = { it.toString() },
                    onSelect = { cpu = it; store.cpuCount = it }
                )
                EngineDropdown(
                    label = stringResource(R.string.engine_ram),
                    options = listOf(1024, 2048, 3072, 4096),
                    selected = ram,
                    optionLabel = { "${it / 1024} GB" },
                    onSelect = { ram = it; store.ramMb = it }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.engine_autostart),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = autoStart,
                        onCheckedChange = { autoStart = it; store.autoStart = it }
                    )
                }
            }

            SectionCard(stringResource(R.string.engine_agent_setup_title)) {
                EngineDropdown(
                    label = stringResource(R.string.engine_provider),
                    options = EngineStore.PROVIDER_KEYS.map { it.first },
                    selected = provider,
                    optionLabel = { it },
                    onSelect = {
                        provider = it
                        credentialName = EngineStore.credentialNameFor(it)
                    }
                )
                LabeledField(
                    label = stringResource(R.string.engine_model),
                    value = model,
                    onValueChange = { model = it },
                    singleLine = true
                )
                LabeledField(
                    label = stringResource(R.string.engine_credential),
                    value = credentialValue,
                    onValueChange = { credentialValue = it },
                    singleLine = true,
                    secret = true
                )
                Text(
                    stringResource(R.string.engine_credential_hint, credentialName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { onApplyAgent(provider, model, credentialName, credentialValue) },
                    enabled = !state.busy && model.isNotBlank() && credentialValue.isNotBlank()
                ) {
                    Text(stringResource(R.string.engine_apply))
                }
            }

            SectionCard(stringResource(R.string.engine_shell_title)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = shellCommand,
                        onValueChange = { shellCommand = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.engine_shell_hint)) },
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.size(8.dp))
                    Button(
                        onClick = { onRunShell(shellCommand) { shellOutput = it } },
                        enabled = shellCommand.isNotBlank() && !state.busy
                    ) {
                        Text(stringResource(R.string.engine_run))
                    }
                }
                if (shellOutput.isNotBlank()) {
                    MonoBox(shellOutput, 160.dp)
                }
            }

            SectionCard(stringResource(R.string.engine_log_title)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRefresh) { Text(stringResource(R.string.engine_refresh)) }
                    TextButton(onClick = { onCopyLog(log.joinToString("\n")) }) {
                        Text(stringResource(R.string.engine_copy_log))
                    }
                }
                MonoBox(if (log.isEmpty()) stringResource(R.string.engine_log_empty) else log.joinToString("\n"), 220.dp)
            }
        }
    }
}

@Composable
private fun EngineStatusCard(
    state: EngineState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestartAgent: () -> Unit,
    onRefresh: () -> Unit
) {
    val vmText = when (state.vm) {
        "running" -> stringResource(R.string.engine_running)
        "starting" -> stringResource(R.string.engine_starting)
        else -> stringResource(R.string.engine_stopped)
    }
    val agentText = when (state.agent) {
        AgentState.READY -> stringResource(R.string.engine_ready)
        AgentState.STARTING -> stringResource(R.string.engine_starting)
        AgentState.STOPPED -> stringResource(R.string.engine_stopped)
        AgentState.FAILED -> stringResource(R.string.engine_failed)
        AgentState.UNKNOWN -> stringResource(R.string.engine_unknown)
    }

    SectionCard(stringResource(R.string.engine_section_status)) {
        InfoRow(stringResource(R.string.engine_vm), vmText)
        InfoRow(
            label = stringResource(R.string.engine_control),
            value = stringResource(if (state.controlReady) R.string.engine_ready else R.string.engine_unknown)
        )
        InfoRow(
            label = stringResource(R.string.engine_dashboard),
            value = stringResource(if (state.dashboardReady) R.string.engine_ready else R.string.engine_unknown)
        )
        InfoRow(stringResource(R.string.engine_agent), agentText)
        if (state.agentVersion.isNotBlank()) InfoRow(stringResource(R.string.engine_version), state.agentVersion)
        if (state.step.isNotBlank()) {
            Text(
                state.step,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.error?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = !state.busy) {
                Text(stringResource(R.string.engine_start))
            }
            OutlinedButton(onClick = onStop, enabled = !state.busy) {
                Text(stringResource(R.string.engine_stop))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRestartAgent, enabled = !state.busy) {
                Text(stringResource(R.string.engine_restart_agent))
            }
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.engine_refresh)) }
        }
    }
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_back))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            content()
        }
    }
}

/** A label/value line; tapping copies the value when a clipboard is supplied. */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    clipboard: androidx.compose.ui.platform.ClipboardManager? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (clipboard != null) {
                    Modifier.clickable { clipboard.setText(AnnotatedString(value)) }
                } else {
                    Modifier
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * One shared single-choice control: a compact accent bar that opens a rounded list. A plain
 * Box + DropdownMenu owns the state once per tap (an ExposedDropdownMenuBox would toggle twice and
 * never open).
 */
@Composable
private fun <T> EngineDropdown(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Box {
            Surface(
                onClick = { expanded = true },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        optionLabel(selected),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(10.dp)
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    secret: Boolean = false
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Fixed-height monospace output box: a plain Column inside its own scroller, never a lazy list. */
@Composable
private fun MonoBox(text: String, height: androidx.compose.ui.unit.Dp) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
        }
    }
}
