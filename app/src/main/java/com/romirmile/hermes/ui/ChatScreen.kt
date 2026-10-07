package com.romirmile.hermes.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.romirmile.hermes.HermesMessage
import com.romirmile.hermes.HermesSession
import com.romirmile.hermes.MessageRole
import com.romirmile.hermes.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: HermesViewModel,
    visible: Boolean,
    onOpenSettings: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenJobs: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenEngine: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val sessions by vm.sessions.collectAsState()
    val currentId by vm.currentId.collectAsState()
    val sending by vm.sending.collectAsState()
    val settings by vm.settings.collectAsState()
    val pendingImage by vm.pendingImage.collectAsState()
    val userNotice by vm.userNotice.collectAsState()
    val voiceActive by vm.voiceSession.collectAsState()

    val session = sessions.firstOrNull { it.id == currentId }
    val messages = session?.messages.orEmpty()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current

    // The chat must never raise the keyboard by itself. This screen stays composed underneath every
    // other screen (they are drawn on top of it), so a one-shot effect at app start was not enough:
    // returning from Settings dropped focus onto the chat's composer — still composed below — and the
    // keyboard came back with it. Keying the clear on visibility covers both directions: leaving clears
    // focus so nothing hands the keyboard to the next screen, and coming back clears it again so the
    // chat opens without one. The composer takes focus when it is tapped, and not before.
    LaunchedEffect(visible) { focusManager.clearFocus(force = true) }

    // The newest message must come back into view when the keyboard opens. Two things make that harder
    // than it looks, and both are handled here:
    //   * the keyboard shrinks the list one of two ways — the IME inset grows (edge-to-edge), or the
    //     window is resized (plain adjustResize, where the IME inset stays 0). Watching the list's own
    //     viewport height covers both, without depending on how insets are dispatched;
    //   * the inset animates over several frames, so an animated scroll finishes before the keyboard has
    //     stopped moving and leaves the list short of the bottom. This re-anchors instantly on every
    //     change instead, and scrolls past the end on purpose so the last message's BOTTOM is visible
    //     (a plain scroll to the last index aligns its top).
    val imeBottom = WindowInsets.ime.getBottom(density)
    val viewportHeight = listState.layoutInfo.viewportSize.height
    var previousViewport by remember { mutableStateOf(0) }
    LaunchedEffect(imeBottom, viewportHeight) {
        val shrank = previousViewport != 0 && viewportHeight < previousViewport
        previousViewport = viewportHeight
        if (messages.isNotEmpty() && (imeBottom > 0 || shrank)) {
            listState.scrollToNewest(messages.lastIndex)
        }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var composer by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<HermesSession?>(null) }
    var deleteTarget by remember { mutableStateOf<HermesSession?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.attachImage(uri) }

    val pickPhoto: () -> Unit = {
        photoPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    val copyText: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    val shareText: (String) -> Unit = { text ->
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_chat_title)))
    }

    LaunchedEffect(userNotice) {
        val notice = userNotice
        if (notice != null) {
            Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
            vm.consumeUserNotice()
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.scrollToNewest(messages.lastIndex)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                sessions = sessions,
                currentId = currentId,
                search = search,
                onSearchChange = { search = it },
                onNewChat = {
                    vm.newChat()
                    scope.launch { drawerState.close() }
                },
                onSelect = { id ->
                    vm.selectChat(id)
                    scope.launch { drawerState.close() }
                },
                onRename = { renameTarget = it },
                onDelete = { deleteTarget = it },
                onOpenJobs = {
                    scope.launch { drawerState.close() }
                    onOpenJobs()
                },
                onOpenSkills = {
                    scope.launch { drawerState.close() }
                    onOpenSkills()
                },
                onOpenModels = {
                    scope.launch { drawerState.close() }
                    onOpenModels()
                },
                onOpenEngine = {
                    scope.launch { drawerState.close() }
                    onOpenEngine()
                },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                }
            )
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                session?.title ?: stringResource(R.string.title_new_chat),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium,
                                fontSize = 17.sp
                            )
                            Text(
                                settings.model.ifBlank { "hermes-agent" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, stringResource(R.string.cd_open_conversations))
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.newChat() }) {
                            Icon(Icons.Default.Edit, stringResource(R.string.cd_new_chat))
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.cd_more))
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                val transcript = messages.joinToString("\n\n") { message ->
                                    val who = if (message.role == MessageRole.USER) "You" else "Hermes"
                                    "$who: ${message.text}"
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_rename_chat)) },
                                    enabled = session != null,
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = {
                                        menuOpen = false
                                        renameTarget = session
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_copy_chat)) },
                                    enabled = transcript.isNotBlank(),
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                    onClick = {
                                        menuOpen = false
                                        copyText(transcript)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_share_chat)) },
                                    enabled = transcript.isNotBlank(),
                                    leadingIcon = { Icon(Icons.Default.Share, null) },
                                    onClick = {
                                        menuOpen = false
                                        shareText(transcript)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_delete_chat)) },
                                    enabled = session != null,
                                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                                    onClick = {
                                        menuOpen = false
                                        deleteTarget = session
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.engine_title)) },
                                    leadingIcon = { Icon(Icons.Default.Memory, null) },
                                    onClick = {
                                        menuOpen = false
                                        onOpenEngine()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_settings)) },
                                    leadingIcon = { Icon(Icons.Default.Settings, null) },
                                    onClick = {
                                        menuOpen = false
                                        onOpenSettings()
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            },
            bottomBar = {
                Column {
                    if (voiceActive) {
                        VoiceReturnBar(
                            onReturn = onOpenVoice,
                            onEnd = { vm.endVoiceSession() }
                        )
                    }
                    Composer(
                        value = composer,
                        onValueChange = { composer = it },
                        sending = sending,
                        hasImage = pendingImage != null,
                        imagePath = pendingImage?.path,
                        onRemoveImage = { vm.clearPendingImage() },
                        onPickPhoto = pickPhoto,
                        onSend = {
                            vm.send(composer)
                            composer = ""
                        },
                        onStop = { vm.stop() },
                        onVoice = onOpenVoice
                    )
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (messages.isEmpty()) {
                    EmptyChat(
                        endpointConfigured = settings.endpoint.isNotBlank(),
                        onOpenSettings = onOpenSettings
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            MessageRow(
                                message = message,
                                onCopy = copyText,
                                onShare = shareText,
                                onRegenerate = { vm.regenerate() }
                            )
                        }
                    }
                }
            }
        }
    }

    val rename = renameTarget
    if (rename != null) {
        var value by remember(rename.id) { mutableStateOf(rename.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.rename_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.rename_field_label)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameChat(rename.id, value)
                    renameTarget = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    val delete = deleteTarget
    if (delete != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_dialog_title)) },
            text = { Text(stringResource(R.string.delete_dialog_message)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteChat(delete.id)
                    deleteTarget = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun VoiceReturnBar(onReturn: () -> Unit, onEnd: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .padding(bottom = 4.dp)
            .clickable { onReturn() }
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.ic_hermes_logo),
                contentDescription = null,
                modifier = Modifier.size(24.dp).clip(CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.voice_session_active),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    stringResource(R.string.voice_return),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onEnd) {
                Text(
                    stringResource(R.string.voice_end_session),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: HermesMessage,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onRegenerate: () -> Unit
) {
    val image = remember(message.imagePath) {
        message.imagePath?.let { path -> BitmapFactory.decodeFile(path)?.asImageBitmap() }
    }

    if (message.role == MessageRole.USER) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) {
                    if (image != null) {
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .widthIn(max = 260.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        if (message.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                    }
                    if (message.text.isNotBlank()) {
                        MessageBody(message.text, streaming = false)
                    }
                }
            }
            MessageActions(
                onCopy = { onCopy(message.text) },
                onRegenerate = null,
                onShare = { onShare(message.text) }
            )
        }
        return
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Avatar()
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.role_hermes),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(5.dp))
            if (message.streaming && message.text.isBlank()) {
                Text(
                    stringResource(R.string.status_thinking) + "…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                MessageBody(
                    text = message.text,
                    streaming = message.streaming,
                    color = if (message.error) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )
            }
            if (message.activity.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                ToolActivityRow(message.activity)
            }
            if (!message.streaming && (message.text.isNotBlank() || message.activity.isNotEmpty())) {
                MessageActions(
                    onCopy = { onCopy(message.text) },
                    onRegenerate = onRegenerate,
                    onShare = { onShare(message.text) }
                )
            }
        }
    }
}

@Composable
private fun MessageActions(
    onCopy: () -> Unit,
    onRegenerate: (() -> Unit)?,
    onShare: () -> Unit
) {
    Row(
        Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ActionIcon(Icons.Default.ContentCopy, R.string.action_copy, onCopy)
        if (onRegenerate != null) {
            ActionIcon(Icons.Default.Refresh, R.string.action_regenerate, onRegenerate)
        }
        ActionIcon(Icons.Default.Share, R.string.action_share, onShare)
    }
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: Int,
    onClick: () -> Unit
) {
    val label = stringResource(labelRes)
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(17.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Avatar() {
    Image(
        painter = painterResource(R.drawable.ic_hermes_logo),
        contentDescription = null,
        modifier = Modifier.size(26.dp).clip(CircleShape)
    )
}

@Composable
private fun EmptyChat(
    endpointConfigured: Boolean,
    onOpenSettings: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_hermes_logo),
            contentDescription = null,
            modifier = Modifier.size(104.dp).clip(CircleShape)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.empty_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(22.dp))
        if (!endpointConfigured) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.error_no_endpoint), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onOpenSettings, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.menu_settings))
                    }
                }
            }
        }
    }
}

/**
 * Lands on the end of the conversation rather than on the start of the last message: scrolling to an
 * index alone puts that item's TOP at the top of the viewport, so a message taller than the viewport
 * (a long answer, an opened terminal tab) leaves its newest lines below the fold. After the index jump
 * this walks forward a viewport at a time until there is nothing left to scroll.
 */
private suspend fun LazyListState.scrollToNewest(lastIndex: Int) {
    scrollToItem(lastIndex)
    var steps = 0
    while (steps < 4 && canScrollForward) {
        scrollBy(layoutInfo.viewportSize.height.toFloat())
        steps++
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    hasImage: Boolean,
    imagePath: String?,
    onRemoveImage: () -> Unit,
    onPickPhoto: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit
) {
    var attachMenu by remember { mutableStateOf(false) }
    val pending = remember(imagePath) {
        imagePath?.let { path -> BitmapFactory.decodeFile(path)?.asImageBitmap() }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp)
            .padding(top = 4.dp, bottom = 8.dp)
    ) {
        if (pending != null) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            bitmap = pending,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(9.dp))
                        )
                        Spacer(Modifier.width(6.dp))
                        IconButton(onClick = onRemoveImage, modifier = Modifier.size(30.dp)) {
                            Icon(
                                Icons.Default.Close,
                                stringResource(R.string.cd_remove_attachment),
                                Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(start = 4.dp, end = 6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Box {
                    IconButton(onClick = { attachMenu = true }) {
                        Icon(Icons.Default.Add, stringResource(R.string.cd_attach))
                    }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_add_photo)) },
                            leadingIcon = { Icon(Icons.Default.Image, null) },
                            onClick = {
                                attachMenu = false
                                onPickPhoto()
                            }
                        )
                    }
                }
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.composer_hint)) },
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )
                Spacer(Modifier.height(4.dp))
                when {
                    sending -> FilledIconButton(
                        onClick = onStop,
                        modifier = Modifier.size(38.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Icon(Icons.Default.Stop, stringResource(R.string.cd_stop), Modifier.size(20.dp))
                    }

                    value.isBlank() && !hasImage -> IconButton(onClick = onVoice) {
                        Icon(Icons.Default.Mic, stringResource(R.string.cd_voice))
                    }

                    else -> FilledIconButton(
                        onClick = onSend,
                        modifier = Modifier.size(38.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Icon(Icons.Default.ArrowUpward, stringResource(R.string.cd_send), Modifier.size(20.dp))
                    }
                }
            }
        }
        Text(
            stringResource(R.string.disclaimer),
            Modifier.fillMaxWidth().padding(top = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/** One drawer entry: icon + label, full-width tap target. */
@Composable
private fun DrawerEntry(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            null,
            Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}

@Composable
private fun ConversationDrawer(
    sessions: List<HermesSession>,
    currentId: String?,
    search: String,
    onSearchChange: (String) -> Unit,
    onNewChat: () -> Unit,
    onSelect: (String) -> Unit,
    onRename: (HermesSession) -> Unit,
    onDelete: (HermesSession) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenJobs: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenEngine: () -> Unit
) {
    val filtered = remember(sessions, search) {
        if (search.isBlank()) {
            sessions
        } else {
            sessions.filter { session ->
                session.title.contains(search, ignoreCase = true) ||
                    session.messages.any { it.text.contains(search, ignoreCase = true) }
            }
        }
    }

    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_hermes_logo),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp).clip(CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.drawer_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onNewChat) {
                    Icon(Icons.Default.Edit, stringResource(R.string.cd_new_chat))
                }
            }
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = onNewChat,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.drawer_new_chat))
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.drawer_search_hint), style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                trailingIcon = {
                    if (search.isNotBlank()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(
                                Icons.Default.Close,
                                stringResource(R.string.cd_clear_search),
                                Modifier.size(16.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.drawer_chats),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { item ->
                    ChatRow(
                        session = item,
                        selected = item.id == currentId,
                        onSelect = { onSelect(item.id) },
                        onRename = { onRename(item) },
                        onDelete = { onDelete(item) }
                    )
                }
                if (filtered.isEmpty() && search.isNotBlank()) {
                    item {
                        Text(
                            stringResource(R.string.drawer_search_empty, search),
                            Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            DrawerEntry(Icons.Default.CheckCircle, stringResource(R.string.drawer_tasks), onOpenJobs)
            DrawerEntry(Icons.Default.Build, stringResource(R.string.drawer_skills), onOpenSkills)
            DrawerEntry(Icons.Default.Tune, stringResource(R.string.drawer_models), onOpenModels)
            DrawerEntry(Icons.Default.Memory, stringResource(R.string.drawer_engine), onOpenEngine)
            DrawerEntry(Icons.Default.Settings, stringResource(R.string.drawer_settings), onOpenSettings)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ChatRow(
    session: HermesSession,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onSelect() },
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                session.title,
                Modifier.weight(1f).padding(vertical = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        stringResource(R.string.cd_chat_options),
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_rename_chat)) },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = {
                            menu = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_delete_chat)) },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = {
                            menu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}
