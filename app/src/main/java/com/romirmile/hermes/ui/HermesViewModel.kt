package com.romirmile.hermes.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.romirmile.hermes.ChatTurn
import com.romirmile.hermes.HermesMessage
import com.romirmile.hermes.HermesSession
import com.romirmile.hermes.MessageRole
import com.romirmile.hermes.PendingImage
import com.romirmile.hermes.R
import com.romirmile.hermes.ToolActivity
import com.romirmile.hermes.data.AppSettings
import com.romirmile.hermes.data.AgentSetup
import com.romirmile.hermes.data.AgentTtsConfig
import com.romirmile.hermes.data.ChatStore
import com.romirmile.hermes.vm.EngineController
import com.romirmile.hermes.vm.EngineStore
import java.io.File
import com.romirmile.hermes.data.CompletionNotifier
import com.romirmile.hermes.data.GatewayAdmin
import com.romirmile.hermes.data.SpeechCatalogue
import com.romirmile.hermes.data.VoiceConfigClient
import com.romirmile.hermes.data.FailureKind
import com.romirmile.hermes.data.HermesClient
import com.romirmile.hermes.data.ImageStore
import com.romirmile.hermes.data.SettingsStore
import com.romirmile.hermes.data.StreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Session id prefix of the one-tap agent-side setup turn (one setup session per install). */
private const val SETUP_SESSION_PREFIX = "android-setup-"

class HermesViewModel(app: Application) : AndroidViewModel(app) {

    private val client = HermesClient()

    private val _sessions = MutableStateFlow<List<HermesSession>>(emptyList())
    val sessions: StateFlow<List<HermesSession>> = _sessions.asStateFlow()

    private val _currentId = MutableStateFlow<String?>(null)
    val currentId: StateFlow<String?> = _currentId.asStateFlow()

    private val _settings = MutableStateFlow(SettingsStore.loadAfterMigrating(app))
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _pendingImage = MutableStateFlow<PendingImage?>(null)
    val pendingImage: StateFlow<PendingImage?> = _pendingImage.asStateFlow()

    /** Feedback line for the Settings screen (test connection / fetch models / save). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** One-shot message shown as a toast by the chat screen. */
    private val _userNotice = MutableStateFlow<String?>(null)
    val userNotice: StateFlow<String?> = _userNotice.asStateFlow()

    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models: StateFlow<List<String>> = _models.asStateFlow()

    /** True while a voice session is open, even if the voice screen has been left. */
    private val _voiceSession = MutableStateFlow(false)
    val voiceSession: StateFlow<Boolean> = _voiceSession.asStateFlow()

    /** Id of the assistant message the voice session has already read aloud. */
    private var spokenMessageId: String? = null

    private var job: Job? = null

    /** One in-flight wait for the on-device engine, shared by every turn queued behind it. */
    private var operativeWait: Deferred<Boolean>? = null

    /**
     * Waits for the on-device engine, reusing a wait that is already running in this view model.
     *
     * There is no race to guard against beyond that reuse: the controller owns exactly one startup
     * operation, and this call joins it (or starts it when nobody owns it). Progress is shown while it
     * lasts; the reason for a failure comes from the controller, which read it off the device.
     */
    private suspend fun ensureOperative(context: Context): Boolean {
        operativeWait?.let { running -> if (running.isActive) return running.await() }
        val wait = viewModelScope.async {
            EngineController.awaitOperative(context) { waited ->
                _userNotice.value = context.getString(R.string.notice_engine_warming, waited)
            }
        }
        operativeWait = wait
        return try {
            wait.await()
        } finally {
            if (operativeWait === wait) operativeWait = null
        }
    }

    private fun engineNotReadyText(context: Context): String {
        val detail = EngineController.lastProblem
        return if (detail.isBlank()) {
            context.getString(R.string.error_engine_not_ready)
        } else {
            context.getString(R.string.error_engine_not_ready_detail, detail)
        }
    }

    /** Chats whose gateway rejected the session header — the header is not retried for them. */
    private val sessionlessChats = mutableSetOf<String>()

    init {
        val loaded = ChatStore.load(app).map { session ->
            // Pre-existing chats get a session id too, so every chat maps to a Hermes session.
            if (session.gatewaySessionId.isBlank()) {
                session.copy(gatewaySessionId = newGatewaySessionId())
            } else {
                session
            }
        }
        _sessions.value = loaded
        _currentId.value = loaded.firstOrNull()?.id ?: createSession()
    }

    /** A fresh Hermes session id: the gateway auto-creates the session on the first turn. */
    private fun newGatewaySessionId(): String = "android-${UUID.randomUUID()}"

    private val context: Context get() = getApplication()

    fun newChat() {
        clearPendingImage()
        _currentId.value = createSession()
    }

    private fun createSession(): String {
        val now = System.currentTimeMillis()
        val session = HermesSession(
            id = UUID.randomUUID().toString(),
            title = context.getString(R.string.title_new_chat),
            createdAt = now,
            updatedAt = now,
            gatewaySessionId = newGatewaySessionId()
        )
        _sessions.value = listOf(session) + _sessions.value
        persist()
        return session.id
    }

    fun selectChat(id: String) {
        if (id != _currentId.value) _currentId.value = id
    }

    fun renameChat(id: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        mutate(id) { it.copy(title = clean, updatedAt = System.currentTimeMillis()) }
        persist()
    }

    fun deleteChat(id: String) {
        _sessions.value = _sessions.value.filterNot { it.id == id }
        if (_currentId.value == id) {
            _currentId.value = _sessions.value.firstOrNull()?.id ?: createSession()
        }
        persist()
    }

    fun updateSettings(update: (AppSettings) -> AppSettings) {
        val next = update(_settings.value)
        _settings.value = next
        SettingsStore.save(context, next)
    }

    // ---- agent voice probing -------------------------------------------------------------

    private val _voiceConfig = MutableStateFlow<AgentTtsConfig?>(null)
    val voiceConfig: StateFlow<AgentTtsConfig?> = _voiceConfig.asStateFlow()

    private val _speechModels = MutableStateFlow<List<SpeechCatalogue.SpeechModel>>(emptyList())
    val speechModels: StateFlow<List<SpeechCatalogue.SpeechModel>> = _speechModels.asStateFlow()

    private val _voiceStatus = MutableStateFlow<String?>(null)
    val voiceStatus: StateFlow<String?> = _voiceStatus.asStateFlow()

    private val _reasoningStatus = MutableStateFlow<String?>(null)
    val reasoningStatus: StateFlow<String?> = _reasoningStatus.asStateFlow()

    /**
     * Reports what the agent says it supports for reasoning on the configured model. The level itself
     * rides with each turn, so this only reads the agent's own answer — it never assumes a capability,
     * and a model that takes no level is said so plainly instead of being advertised as working.
     */
    fun refreshReasoning() {
        val config = _settings.value
        if (config.endpoint.isBlank()) {
            _reasoningStatus.value = context.getString(R.string.settings_need_endpoint)
            return
        }
        val model = config.model.ifBlank { HermesClient.DEFAULT_MODEL }
        viewModelScope.launch {
            _reasoningStatus.value = context.getString(R.string.settings_reasoning_probing, model)
            val attempt = withContext(Dispatchers.IO) {
                client.probeChat(config.endpoint, config.apiKey, config.model)
            }
            val probe = attempt.getOrNull()
            val failure = attempt.exceptionOrNull()
            _reasoningStatus.value = when {
                failure is HermesClient.AgentReplyException &&
                    failure.httpCode in listOf(400, 404) ->
                    // The one failure that is really about the model name.
                    context.getString(
                        R.string.settings_reasoning_model_rejected,
                        model,
                        failure.message.orEmpty().ifBlank { "HTTP ${failure.httpCode}" }
                    )

                failure is HermesClient.AgentReplyException ->
                    context.getString(R.string.settings_reasoning_agent_error, failure.message.orEmpty())

                failure != null ->
                    context.getString(
                        R.string.settings_reasoning_offline,
                        failure.message.orEmpty().ifBlank { "unreachable" }
                    )

                else -> {
                    // The agent answered a real turn: the alias works. The model listing below is only an
                    // enrichment — a model the agent does not list can still answer, so it never decides this.
                    val line = context.getString(
                        R.string.settings_reasoning_ok,
                        probe?.agentVersion ?: "?",
                        probe?.model.orEmpty().ifBlank { model }
                    )
                    val capability = withContext(Dispatchers.IO) {
                        runCatching {
                            client.reasoningSupport(config.endpoint, config.apiKey, config.model).getOrNull()
                        }.getOrNull()
                    }
                    if (capability != null && !capability.reasoning) {
                        "$line ${context.getString(R.string.settings_reasoning_ok_no_reasoning)}"
                    } else {
                        line
                    }
                }
            }
        }
    }

    /**
     * Asks the agent what it can do for speech: `direct` means it handed over an endpoint and a
     * credential, so the app can pick the model itself; `relay` means the agent speaks with its own
     * voice. For an OpenAI-compatible endpoint the app then lists the models that endpoint offers —
     * free ones first — so the user chooses explicitly instead of trusting a default.
     */
    fun refreshVoice() {
        val config = _settings.value
        if (config.endpoint.isBlank()) {
            _voiceStatus.value = context.getString(R.string.settings_need_endpoint)
            return
        }
        viewModelScope.launch {
            _voiceStatus.value = context.getString(R.string.voice_status_checking)
            val attempt = withContext(Dispatchers.IO) {
                runCatching { VoiceConfigClient.fetch(config.endpoint, config.apiKey) }
            }
            val resolved = attempt.getOrNull()
            _voiceConfig.value = resolved
            if (resolved == null) {
                _speechModels.value = emptyList()
                val detail = attempt.exceptionOrNull()?.message.orEmpty().ifBlank { "unreachable" }
                _voiceStatus.value = context.getString(R.string.voice_status_none, detail)
                return@launch
            }
            _voiceStatus.value = when {
                resolved.isDirect && resolved.isOpenRouter ->
                    context.getString(R.string.voice_status_direct_openrouter)
                resolved.isDirect ->
                    context.getString(R.string.voice_status_direct, resolved.provider ?: "?")
                else -> context.getString(R.string.voice_status_relay)
            }
            _speechModels.value = if (resolved.isDirect && resolved.isOpenRouter) {
                withContext(Dispatchers.IO) {
                    runCatching { SpeechCatalogue.openRouter() }.getOrNull()
                }.orEmpty()
            } else {
                emptyList()
            }
        }
    }

    // ---- one-tap agent-side setup ---------------------------------------------------------

    private val _agentSetupStatus = MutableStateFlow<String?>(null)
    val agentSetupStatus: StateFlow<String?> = _agentSetupStatus.asStateFlow()

    private val _agentSetupRunning = MutableStateFlow(false)
    val agentSetupRunning: StateFlow<Boolean> = _agentSetupRunning.asStateFlow()

    /**
     * Asks the agent to install its own speech endpoints (see [AgentSetup]): the app sends the
     * bundled plugin and the exact commands, then shows what the agent reports. A gateway restart on
     * the agent's host is still required before the routes answer, which is why the UI keeps that
     * command and the Check-agent-voice button beside this status.
     */
    fun installAgentVoice() {
        val config = _settings.value
        if (config.endpoint.isBlank()) {
            _agentSetupStatus.value = context.getString(R.string.settings_need_endpoint)
            return
        }
        if (!AgentSetup.isAvailable(context)) {
            _agentSetupStatus.value = context.getString(R.string.agent_setup_unavailable)
            return
        }
        if (_agentSetupRunning.value) return
        val prompt = AgentSetup.prompt(context)
        viewModelScope.launch {
            _agentSetupRunning.value = true
            _agentSetupStatus.value = context.getString(R.string.settings_agent_setup_running)
            val memoryKey = SettingsStore.installId(context)
            val reply = withContext(Dispatchers.IO) {
                val turns = listOf(ChatTurn(role = "user", text = prompt))
                val collected = StringBuilder()
                runCatching {
                    client.streamChat(
                        baseUrl = config.endpoint,
                        apiKey = config.apiKey,
                        model = config.model,
                        turns = turns,
                        gatewaySessionId = "$SETUP_SESSION_PREFIX$memoryKey",
                        memoryKey = memoryKey
                    ).collect { event ->
                        when (event) {
                            is StreamEvent.Delta -> collected.append(event.text)
                            is StreamEvent.Failed -> collected.append(event.detail)
                            else -> Unit
                        }
                    }
                }
                collected.toString().trim()
            }
            _agentSetupRunning.value = false
            _agentSetupStatus.value = reply.ifBlank {
                context.getString(R.string.agent_setup_no_reply)
            }
        }
    }

    // ---- agent admin: scheduled jobs, skills, and the "reply is ready" notification -------------

    private val _jobs = MutableStateFlow<List<GatewayAdmin.Job>>(emptyList())
    val jobs: StateFlow<List<GatewayAdmin.Job>> = _jobs.asStateFlow()

    private val _skills = MutableStateFlow<List<GatewayAdmin.Skill>>(emptyList())
    val skills: StateFlow<List<GatewayAdmin.Skill>> = _skills.asStateFlow()

    private val _adminLoading = MutableStateFlow(false)
    val adminLoading: StateFlow<Boolean> = _adminLoading.asStateFlow()

    private val _adminNotice = MutableStateFlow<String?>(null)
    val adminNotice: StateFlow<String?> = _adminNotice.asStateFlow()

    /**
     * Set by the activity: a finished reply only raises a system notification while the app is not in
     * the foreground, so nobody gets buzzed while already reading the answer.
     */
    @Volatile
    var appVisible: Boolean = true

    fun loadJobs() {
        val config = _settings.value
        if (config.endpoint.isBlank()) {
            _adminNotice.value = context.getString(R.string.settings_need_endpoint)
            return
        }
        viewModelScope.launch {
            _adminLoading.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching { GatewayAdmin.jobs(config.endpoint, config.apiKey) }
            }
            _adminLoading.value = false
            result.onSuccess { jobs ->
                _jobs.value = jobs
                _adminNotice.value = null
            }.onFailure {
                _adminNotice.value = it.message ?: context.getString(R.string.admin_unavailable)
            }
        }
    }

    fun loadSkills() {
        val config = _settings.value
        if (config.endpoint.isBlank()) {
            _adminNotice.value = context.getString(R.string.settings_need_endpoint)
            return
        }
        viewModelScope.launch {
            _adminLoading.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching { GatewayAdmin.skills(config.endpoint, config.apiKey) }
            }
            _adminLoading.value = false
            result.onSuccess { skills ->
                _skills.value = skills
                _adminNotice.value = null
            }.onFailure {
                _adminNotice.value = it.message ?: context.getString(R.string.admin_unavailable)
            }
        }
    }

    fun runJob(id: String) = jobAction(R.string.tasks_running_named) { config ->
        GatewayAdmin.runJob(config.endpoint, config.apiKey, id)
    }

    fun pauseJob(id: String) = jobAction(R.string.tasks_pausing_named) { config ->
        GatewayAdmin.pauseJob(config.endpoint, config.apiKey, id)
    }

    fun resumeJob(id: String) = jobAction(R.string.tasks_resuming_named) { config ->
        GatewayAdmin.resumeJob(config.endpoint, config.apiKey, id)
    }

    fun deleteJob(id: String) = jobAction(R.string.tasks_deleting_named) { config ->
        GatewayAdmin.deleteJob(config.endpoint, config.apiKey, id)
    }

    /** Runs one job control call, reports the outcome, and refreshes the list either way. */
    private fun jobAction(label: Int, block: suspend (AppSettings) -> Unit) {
        val config = _settings.value
        if (config.endpoint.isBlank()) {
            _adminNotice.value = context.getString(R.string.settings_need_endpoint)
            return
        }
        viewModelScope.launch {
            _adminLoading.value = true
            _adminNotice.value = context.getString(label)
            val result = withContext(Dispatchers.IO) { runCatching { block(config) } }
            _adminLoading.value = false
            _adminNotice.value = result.fold(
                onSuccess = { null },
                onFailure = { it.message ?: context.getString(R.string.admin_unavailable) }
            )
            loadJobs()
        }
    }

    /** Raises the completion notification for a reply that finished while the app was away. */
    private fun notifyReplyReady(sessionId: String, assistantId: String) {
        val session = _sessions.value.firstOrNull { it.id == sessionId } ?: return
        val message = session.messages.firstOrNull { it.id == assistantId } ?: return
        val text = message.text.trim()
        if (text.isBlank() || message.error) return
        CompletionNotifier.notify(
            context = context,
            title = session.title.ifBlank { context.getString(R.string.app_name) },
            body = text.replace(Regex("\\s+"), " ").take(240)
        )
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun consumeUserNotice() {
        _userNotice.value = null
    }

    fun testConnection(endpoint: String, apiKey: String) {
        if (endpoint.isBlank()) {
            _notice.value = context.getString(R.string.settings_need_endpoint)
            return
        }
        viewModelScope.launch {
            _notice.value = context.getString(R.string.settings_testing)
            val result = client.checkConnection(endpoint, apiKey)
            _notice.value = result.fold(
                onSuccess = { info ->
                    val version = info.hermesVersion
                    if (version != null) context.getString(R.string.settings_test_ok, version)
                    else context.getString(R.string.settings_test_ok_gateway, info.modelCount)
                },
                onFailure = { context.getString(R.string.settings_test_fail, it.message ?: "?") }
            )
        }
    }

    fun fetchModels(endpoint: String, apiKey: String) {
        if (endpoint.isBlank()) {
            _notice.value = context.getString(R.string.settings_need_endpoint)
            return
        }
        viewModelScope.launch {
            _notice.value = context.getString(R.string.settings_fetching)
            client.listModels(endpoint, apiKey).fold(
                onSuccess = { ids ->
                    _models.value = ids
                    _notice.value = if (ids.isEmpty()) context.getString(R.string.settings_no_models)
                    else context.getString(R.string.settings_models_found, ids.size)
                },
                onFailure = {
                    _models.value = emptyList()
                    _notice.value = context.getString(R.string.settings_test_fail, it.message ?: "?")
                }
            )
        }
    }

    fun saveConnection(endpoint: String, apiKey: String, model: String) {
        updateSettings {
            it.copy(
                endpoint = endpoint.trim(),
                apiKey = apiKey.trim(),
                model = model.trim().ifBlank { HermesClient.DEFAULT_MODEL }
            )
        }
        _notice.value = context.getString(R.string.settings_saved)
    }

    // ---- attachments -------------------------------------------------------------------

    fun attachImage(uri: Uri) {
        viewModelScope.launch {
            val pending = withContext(Dispatchers.IO) { ImageStore.import(context, uri) }
            if (pending == null) {
                _userNotice.value = context.getString(R.string.notice_image_failed)
            } else {
                _pendingImage.value = pending
            }
        }
    }

    fun clearPendingImage() {
        _pendingImage.value = null
    }

    // ---- voice session ------------------------------------------------------------------

    /** Leaving the voice screen keeps the session alive so it can be resumed from the chat. */
    fun startVoiceSession() {
        _voiceSession.value = true
    }

    fun endVoiceSession() {
        _voiceSession.value = false
        spokenMessageId = null
    }

    fun hasSpoken(messageId: String?): Boolean = messageId != null && spokenMessageId == messageId

    fun markSpoken(messageId: String?) {
        spokenMessageId = messageId
    }

    // ---- generation --------------------------------------------------------------------

    fun stop() {
        client.stop()
        job?.cancel()
        job = null
        finalizeStream(appendStopped = true)
        _sending.value = false
    }

    fun send(text: String) {
        val trimmed = text.trim()
        val pending = _pendingImage.value
        if (trimmed.isEmpty() && pending == null) return
        if (_sending.value) return
        val sessionId = _currentId.value ?: return
        val now = System.currentTimeMillis()

        val userMessage = HermesMessage(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = MessageRole.USER,
            text = trimmed,
            timestamp = now,
            imagePath = pending?.path
        )
        val isFirstUserMessage = _sessions.value
            .firstOrNull { it.id == sessionId }
            ?.messages?.none { it.role == MessageRole.USER } ?: true

        mutate(sessionId) { session ->
            session.copy(
                messages = session.messages + userMessage,
                title = if (isFirstUserMessage) autoTitle(trimmed.ifBlank { "Image" }) else session.title,
                updatedAt = now
            )
        }
        _pendingImage.value = null
        startStream(sessionId)
    }

    /** Drop the last answer and run the turn again from the last user message. */
    fun regenerate() {
        if (_sending.value) return
        val sessionId = _currentId.value ?: return
        val messages = _sessions.value.firstOrNull { it.id == sessionId }?.messages.orEmpty()
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex < 0) return
        mutate(sessionId) { it.copy(messages = it.messages.take(lastUserIndex + 1)) }
        startStream(sessionId)
    }

    /**
     * Decides whether the on-device engine has to be prepared, prepares it if it does, and hands the
     * turn to [startStreamReady].
     *
     * This function is entered **once per user turn**: it never calls itself, so a successful startup
     * cannot lead back into another startup. For a local endpoint the readiness decision is made here,
     * by `EngineController.awaitOperative()` — the controller's own authority, which joins a startup
     * already in flight instead of launching a second one — and the turn then starts directly.
     */
    private fun startStream(sessionId: String) {
        val config = _settings.value
        if (config.endpoint.isBlank()) {
            appendError(sessionId, context.getString(R.string.error_no_endpoint))
            persist()
            return
        }

        if (!isOnline()) {
            appendError(sessionId, context.getString(R.string.error_offline))
            persist()
            return
        }

        // A user-supplied endpoint has nothing to prepare: the turn starts immediately.
        if (config.endpoint.trimEnd('/') != EngineStore.localEndpoint()) {
            startStreamReady(sessionId)
            return
        }

        // The engine runs on this device and a cold guest needs minutes before its gateway binds, so a
        // turn sent into that window waits for the port instead of dying on it. `_sending` stays on for
        // the whole wait (the turn's own finally clears it), and progress goes out as a notice.
        job = viewModelScope.launch {
            _sending.value = true
            val ready = ensureOperative(context)
            _userNotice.value = null
            if (!ready) {
                _sending.value = false
                job = null
                appendError(sessionId, engineNotReadyText(context))
                persist()
                return@launch
            }
            // Ready: go straight to the chat turn. No re-check of readiness and no re-entry into this
            // function - one user turn, one engine preparation, one chat request.
            EngineController.logLine("engine ready — proceeding directly to chat turn")
            job = null
            startStreamReady(sessionId)
        }
    }

    /**
     * Starts the chat turn itself for an engine that is ready: the assistant placeholder, the
     * idempotency key and the streaming request. The readiness decision belongs to [startStream];
     * nothing here inspects the engine, and nothing here calls [startStream].
     */
    private fun startStreamReady(sessionId: String) {
        EngineController.logLine("starting chat turn")
        val assistantId = UUID.randomUUID().toString()
        mutate(sessionId) { session ->
            session.copy(
                messages = session.messages + HermesMessage(
                    id = assistantId,
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    text = "",
                    timestamp = System.currentTimeMillis(),
                    streaming = true
                )
            )
        }

        _sending.value = true
        val idempotencyKey = UUID.randomUUID().toString()
        job = viewModelScope.launch {
            try {
                runTurn(sessionId, assistantId, idempotencyKey)
            } finally {
                if (job?.isCancelled != true) {
                    updateAssistant(sessionId, assistantId) { it.copy(streaming = false) }
                    _sending.value = false
                    job = null
                    persist()
                    if (!appVisible) notifyReplyReady(sessionId, assistantId)
                }
            }
        }
    }

    /**
     * Streams one turn. A network change (Wi-Fi ↔ mobile, tunnel flap) kills the open socket before
     * any token arrives; in that case the turn is retried with the SAME idempotency key so the
     * gateway dedupes instead of running the agent twice. Once text has arrived, what the user
     * already sees is kept and Regenerate stays a deliberate choice.
     */
    private suspend fun runTurn(sessionId: String, assistantId: String, idempotencyKey: String) {
        var attempt = 0
        // A gateway that doesn't understand session headers must not break chat: the header is
        // dropped for this chat after the first client-side rejection and never sent again.
        var gatewaySession = _sessions.value.firstOrNull { it.id == sessionId }
            ?.gatewaySessionId
            ?.takeIf { it.isNotBlank() && it !in sessionlessChats }
        while (true) {
            attempt++
            val config = _settings.value
            val buffer = StringBuilder()
            var failure: StreamEvent.Failed? = null

            val turns = withContext(Dispatchers.IO) { buildTurns(sessionId) }
            client.streamChat(
                config.endpoint,
                config.apiKey,
                config.model,
                turns,
                idempotencyKey,
                gatewaySessionId = gatewaySession,
                memoryKey = "agent:hermes-android:${SettingsStore.installId(context)}",
                reasoning = config.reasoning
            ).collect { event ->
                when (event) {
                    is StreamEvent.Delta -> {
                        buffer.append(event.text)
                        updateAssistant(sessionId, assistantId) { it.copy(text = buffer.toString()) }
                    }

                    is StreamEvent.ToolStarted -> addTool(
                        sessionId, assistantId,
                        ToolActivity(
                            id = UUID.randomUUID().toString(),
                            tool = event.tool,
                            preview = event.preview,
                            running = true
                        )
                    )

                    is StreamEvent.ToolFinished -> finishTool(
                        sessionId, assistantId, event.tool, event.duration, event.error, event.preview
                    )

                    is StreamEvent.Failed -> failure = event

                    StreamEvent.Completed -> Unit
                }
            }

            val failed = failure ?: return

            // The gateway refused the turn while a session id was attached (e.g. a build that
            // validates session ids instead of creating them): retry once without the header.
            val code = failed.httpCode
            if (gatewaySession != null && buffer.isEmpty() && code != null && code in 400..499) {
                sessionlessChats.add(sessionId)
                gatewaySession = null
                continue
            }
            val canRetry = failed.retryable && buffer.isEmpty() && attempt < MAX_ATTEMPTS
            if (!canRetry) {
                updateAssistant(sessionId, assistantId) { message ->
                    val prefix = if (message.text.isBlank()) "" else "\n\n"
                    message.copy(text = message.text + prefix + failureText(failed), error = true)
                }
                return
            }

            // Nothing was shown yet, so reconnecting is invisible except for the toast.
            if (attempt == 1) {
                _userNotice.value = context.getString(R.string.notice_reconnecting)
            }
            updateAssistant(sessionId, assistantId) { it.copy(text = "") }
            delay(if (attempt == 1) 1_500L else 4_000L)
        }
    }

    private fun failureText(failed: StreamEvent.Failed): String = when (failed.kind) {
        FailureKind.OFFLINE -> context.getString(R.string.error_offline)
        FailureKind.HOST_UNREACHABLE -> context.getString(R.string.error_host_unreachable)
        FailureKind.TIMEOUT -> context.getString(R.string.error_timeout)
        FailureKind.CONNECTION_LOST -> context.getString(R.string.error_connection_lost)
        FailureKind.HTTP, FailureKind.UNKNOWN -> failed.detail
    }

    /** Cheap connectivity probe so an offline phone gets a clear message instead of a socket error. */
    private fun isOnline(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Full local transcript for a session; the streaming placeholder is excluded. */
    private fun buildTurns(sessionId: String): List<ChatTurn> =
        _sessions.value.firstOrNull { it.id == sessionId }
            ?.messages
            ?.filter { it.role != MessageRole.SYSTEM && !it.error && !it.streaming }
            ?.filter { it.text.isNotBlank() || it.imagePath != null }
            ?.map { message ->
                ChatTurn(
                    role = message.role.name.lowercase(),
                    text = message.text,
                    imageDataUrl = ImageStore.dataUrlFor(message.imagePath)
                )
            }
            .orEmpty()

    private fun finalizeStream(appendStopped: Boolean) {
        val sessionId = _currentId.value ?: return
        val session = _sessions.value.firstOrNull { it.id == sessionId } ?: return
        val streaming = session.messages.lastOrNull { it.streaming } ?: return
        val text = if (streaming.text.isBlank() && appendStopped) {
            context.getString(R.string.status_stopped)
        } else {
            streaming.text
        }
        updateAssistant(sessionId, streaming.id) { it.copy(text = text, streaming = false) }
        persist()
    }

    private fun appendError(sessionId: String, message: String) {
        mutate(sessionId) { session ->
            session.copy(
                messages = session.messages + HermesMessage(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    text = message,
                    timestamp = System.currentTimeMillis(),
                    error = true
                ),
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private fun autoTitle(text: String): String =
        if (text.length <= 48) text else text.take(48).trimEnd() + "…"

    private fun mutate(sessionId: String, block: (HermesSession) -> HermesSession) {
        _sessions.value = _sessions.value.map { if (it.id == sessionId) block(it) else it }
    }

    private fun updateAssistant(
        sessionId: String,
        messageId: String,
        block: (HermesMessage) -> HermesMessage
    ) {
        mutate(sessionId) { session ->
            session.copy(
                messages = session.messages.map { if (it.id == messageId) block(it) else it },
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private fun addTool(sessionId: String, messageId: String, activity: ToolActivity) {
        updateAssistant(sessionId, messageId) { message ->
            message.copy(activity = message.activity.dropLastWhile { !it.running } + activity)
        }
    }

    private fun finishTool(
        sessionId: String,
        messageId: String,
        tool: String,
        duration: Double?,
        error: Boolean,
        preview: String
    ) {
        updateAssistant(sessionId, messageId) { message ->
            val index = message.activity.indexOfLast { it.running && it.tool == tool }
            val updated = if (index >= 0) {
                message.activity.toMutableList().also {
                    val current = it[index]
                    it[index] = current.copy(
                        running = false,
                        error = error,
                        duration = duration,
                        preview = preview.ifBlank { current.preview }
                    )
                }
            } else {
                message.activity + ToolActivity(
                    id = UUID.randomUUID().toString(),
                    tool = tool,
                    preview = preview,
                    running = false,
                    error = error,
                    duration = duration
                )
            }
            message.copy(activity = updated)
        }
    }

    private fun persist() {
        ChatStore.save(context, _sessions.value)
    }

    private companion object {
        /** Total tries for one turn when the socket dies before any token arrives. */
        const val MAX_ATTEMPTS = 3
    }
}
