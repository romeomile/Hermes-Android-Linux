package com.romirmile.hermes.vm

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AgentState { UNKNOWN, STOPPED, STARTING, READY, FAILED }

data class EngineState(
    val vm: String = "stopped",
    val controlReady: Boolean = false,
    val agent: AgentState = AgentState.UNKNOWN,
    val agentVersion: String = "",
    val busy: Boolean = false,
    val step: String = "",
    val error: String? = null
)

/**
 * Brings the engine up in the right order and keeps the UI informed at every step.
 *
 * A single launcher for the app: the VM process, the guest control API and the agent inside the
 * guest all have to come up in sequence, each one can take a while under emulation, and the user
 * needs to see which step is running instead of a spinner that might be stuck.
 */
object EngineController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var managerInstance: VmManager? = null

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun manager(context: Context): VmManager = managerInstance ?: synchronized(this) {
        managerInstance ?: VmManager(context.applicationContext).also { created ->
            created.onLog = { line -> logLine(line) }
            created.onProgress = { message -> logLine(message) }
            managerInstance = created
        }
    }

    fun logLine(text: String) {
        val line = "${stamp.format(Date())}  $text"
        _log.value = (_log.value + line).takeLast(LOG_LIMIT)
    }

    fun endpoint(): String = EngineStore.localEndpoint()

    fun token(context: Context): String = EngineStore(context.applicationContext).token

    /**
     * Starts the engine from wherever it currently is: safe to call on launch, from a button, or
     * again after a failure. [force] restarts a VM that is already up.
     */
    fun start(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = _state.value.copy(busy = true, error = null, vm = "starting", step = "")
            try {
                val manager = manager(app)
                val api = manager.apiClient

                if (manager.isRunning() && !force) {
                    logLine("engine already running")
                } else {
                    step("Starting the foreground service")
                    VmService.start(app)
                    step("Booting the Linux VM")
                    _state.value = _state.value.copy(vm = "starting")
                    manager.start()
                    _state.value = _state.value.copy(vm = "running")
                }

                step("Waiting for the guest control API")
                if (!awaitControl(api, CONTROL_TIMEOUT_SECONDS)) {
                    throw IllegalStateException("the guest control API did not answer within ${CONTROL_TIMEOUT_SECONDS}s")
                }
                _state.value = _state.value.copy(controlReady = true)

                step("Starting the agent inside the guest")
                _state.value = _state.value.copy(agent = AgentState.STARTING)
                var status = api.agentStatus()
                if (status == null) throw IllegalStateException("the guest control API rejected the request")
                if (!status.installed) throw IllegalStateException("Hermes is not installed in the guest image")
                if (!status.running) {
                    if (!api.startAgent()) logLine("agent start reported a failure — check the log below")
                }

                val ready = awaitAgent(api, AGENT_TIMEOUT_SECONDS)
                val finalStatus = api.agentStatus()
                _state.value = _state.value.copy(
                    agent = if (ready) AgentState.READY else AgentState.FAILED,
                    agentVersion = finalStatus?.version ?: "",
                    error = if (ready) null else "the agent did not become ready within ${AGENT_TIMEOUT_SECONDS}s"
                )
                if (ready) {
                    logLine("agent ready on ${EngineStore.localEndpoint()}")
                    step("Engine ready")
                }
            } catch (e: Exception) {
                logLine("error: ${e.message}")
                _state.value = _state.value.copy(error = e.message ?: "engine start failed")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                runCatching { manager(app).apiClient.stopAgent() }
                runCatching { manager(app).stop() }
                VmService.stop(app)
            } finally {
                _state.value = EngineState(vm = "stopped", agent = AgentState.UNKNOWN)
                logLine("engine stopped")
            }
        }
    }

    /** Cheap poll used when the screen opens, so the display matches reality. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val manager = manager(app)
            val vm = manager.status()
            val control = vm == "running" && manager.apiClient.health()
            val agent = if (control) manager.apiClient.agentStatus() else null
            _state.value = _state.value.copy(
                vm = vm,
                controlReady = control,
                agent = when {
                    agent == null -> _state.value.agent
                    agent.running -> AgentState.READY
                    else -> AgentState.STOPPED
                },
                agentVersion = agent?.version ?: _state.value.agentVersion
            )
        }
    }

    fun restartAgent(context: Context, onDone: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        scope.launch {
            _state.value = _state.value.copy(busy = true, agent = AgentState.STARTING)
            val api = manager(app).apiClient
            api.stopAgent()
            val started = api.startAgent()
            val ready = awaitAgent(api, AGENT_TIMEOUT_SECONDS)
            _state.value = _state.value.copy(
                busy = false,
                agent = if (ready) AgentState.READY else AgentState.FAILED,
                error = if (ready) null else "the agent did not come back within ${AGENT_TIMEOUT_SECONDS}s"
            )
            logLine(if (ready) "agent restarted" else "agent restart reported a failure")
            onDone(ready && started || ready)
        }
    }

    /** Writes the model provider, model and credential, then restarts the agent to apply them. */
    fun configureAgent(
        context: Context,
        provider: String,
        model: String,
        credentialName: String,
        credentialValue: String,
        onDone: (Boolean) -> Unit
    ) {
        val app = context.applicationContext
        scope.launch {
            _state.value = _state.value.copy(busy = true)
            val store = EngineStore(app)
            store.provider = provider.trim()
            store.model = model.trim()
            val settings = buildMap {
                if (provider.isNotBlank()) put("model.provider", provider.trim())
                if (model.isNotBlank()) put("model.default", model.trim())
            }
            val env = if (credentialName.isNotBlank() && credentialValue.isNotBlank()) {
                mapOf(credentialName.trim() to credentialValue.trim())
            } else {
                emptyMap()
            }
            val ok = manager(app).apiClient.configureAgent(env, settings)
            logLine(if (ok) "agent configuration written" else "agent configuration failed")
            restartAgent(app) { ready -> onDone(ok && ready) }
        }
    }

    fun shell(context: Context, command: String, timeoutSeconds: Int = 60, onResult: (String) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            val result = try {
                manager(app).vmExec(command, timeoutSeconds)
            } catch (e: Exception) {
                VmApiClient.ExecResult("", e.message ?: "command failed", -1, false)
            }
            val text = buildString {
                append(result.stdout)
                if (result.stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(result.stderr)
                }
                if (isEmpty()) append("(no output)")
                append("\n[exit ${result.exitCode}${if (result.timedOut) ", timed out" else ""}]")
            }
            onResult(text)
        }
    }

    fun fetchAgentLog(context: Context, lines: Int = 200, onResult: (String) -> Unit) {
        val app = context.applicationContext
        scope.launch { onResult(manager(app).apiClient.agentLog(lines)) }
    }

    private suspend fun awaitControl(api: VmApiClient, timeoutSeconds: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (api.health()) return true
            delay(1_000)
        }
        return false
    }

    private suspend fun awaitAgent(api: VmApiClient, timeoutSeconds: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (api.agentStatus()?.running == true) return true
            delay(2_000)
        }
        return false
    }

    private fun step(message: String) {
        _state.value = _state.value.copy(step = message)
        logLine(message)
    }

    private const val LOG_LIMIT = 400
    private const val CONTROL_TIMEOUT_SECONDS = 180
    private const val AGENT_TIMEOUT_SECONDS = 300
}
