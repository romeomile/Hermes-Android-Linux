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

    /** Last line QEMU wrote to the serial console, quoted when the VM dies during startup. */
    private var lastVmLine: String = ""

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun manager(context: Context): VmManager = managerInstance ?: synchronized(this) {
        managerInstance ?: VmManager(context.applicationContext).also { created ->
            created.onLog = { line -> lastVmLine = line; logLine(line) }
            created.onProgress = { message -> logLine(message) }
            managerInstance = created
        }
    }

    fun logLine(text: String) {
        val line = "${stamp.format(Date())}  $text"
        _log.value = (_log.value + line).takeLast(LOG_LIMIT)
    }

    fun endpoint(): String = EngineStore.localEndpoint()

    /** True when the agent's API is answering on the port the chat itself uses. */
    fun isOperative(): Boolean = managerInstance?.apiClient?.agentApiAnswers(2_000) == true

    /**
     * Waits until the agent answers on the port the chat uses, starting the engine if it is not up.
     *
     * A cold guest needs minutes before its gateway binds, and a turn sent into that window used to
     * die with "connection lost". Waiting here keeps the turn instead: it is sent the moment the port
     * answers. [onProgress] receives the elapsed seconds so the UI can say what it is waiting for.
     */
    suspend fun awaitOperative(
        context: Context,
        timeoutSeconds: Int = OPERATIVE_TIMEOUT_SECONDS,
        onProgress: (Int) -> Unit = {}
    ): Boolean {
        val app = context.applicationContext
        val manager = manager(app)
        if (manager.apiClient.agentApiAnswers(2_000)) return true
        if (!manager.isRunning()) start(app)
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        var reported = -1
        while (System.currentTimeMillis() < deadline) {
            if (manager.apiClient.agentApiAnswers(2_000)) return true
            val waited = ((System.currentTimeMillis() - (deadline - timeoutSeconds * 1000L)) / 1000L).toInt()
            if (waited != reported && waited % 5 == 0) {
                reported = waited
                onProgress(waited)
            }
            delay(2_000)
        }
        return false
    }

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
                    logLine(
                        "ports: device ${EngineStore.CONTROL_PORT}/${EngineStore.AGENT_PORT} " +
                            "-> guest ${EngineStore.GUEST_CONTROL_PORT}/${EngineStore.GUEST_AGENT_PORT}"
                    )
                    _state.value = _state.value.copy(vm = "starting")
                    manager.start()
                    _state.value = _state.value.copy(vm = "running")
                }

                step("Waiting for the guest control API")
                if (!awaitControl(api, manager, CONTROL_TIMEOUT_SECONDS)) {
                    throw IllegalStateException("the guest control API did not answer within ${CONTROL_TIMEOUT_SECONDS}s")
                }
                _state.value = _state.value.copy(controlReady = true)

                step("Preparing the guest")
                _state.value = _state.value.copy(agent = AgentState.STARTING)
                val status = api.agentStatus()
                if (status == null) throw IllegalStateException("the guest control API rejected the request")
                if (!status.installed) throw IllegalStateException("Hermes is not installed in the guest image")
                ensureGuestKey(api, manager)

                // The guest's own readiness answer is a claim, not evidence: its `agent_running()` is
                // `kill -0` on a pidfile and guest pids get reused, so it reports an agent that is
                // alive as a process while nothing is bound to the port. The port the chat itself uses
                // is the only thing accepted here.
                var ready = api.agentApiAnswers()
                if (!ready) {
                    step("Starting the agent inside the guest")
                    startAgentFromScratch(api, manager)
                    ready = awaitAgentPort(api, AGENT_TIMEOUT_SECONDS)
                    if (!ready) {
                        throw IllegalStateException(
                            "the agent never answered on ${EngineStore.localEndpoint()} within " +
                                "${AGENT_TIMEOUT_SECONDS}s — guest agent log: ${agentLogTail(api)}"
                        )
                    }
                }
                val finalStatus = api.agentStatus()
                _state.value = _state.value.copy(
                    agent = AgentState.READY,
                    agentVersion = finalStatus?.version ?: "",
                    error = null
                )
                logLine("agent ready on ${EngineStore.localEndpoint()} (the port answered)")
                step("Engine ready")
            } catch (e: Exception) {
                logLine("error: ${e.message}")
                // Report the state that is true after the failure instead of leaving the last
                // progress line standing: a screen that says "stopped" and "starting" at once is
                // how this failure used to read.
                val alive = managerInstance?.status() == "running"
                _state.value = _state.value.copy(
                    error = e.message ?: "engine start failed",
                    vm = if (alive) "running" else "stopped",
                    controlReady = alive && _state.value.controlReady,
                    agent = if (alive) _state.value.agent else AgentState.STOPPED
                )
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
            val manager = manager(app)
            val api = manager.apiClient
            api.stopAgent()
            startAgentFromScratch(api, manager)
            val ready = awaitAgentPort(api, AGENT_TIMEOUT_SECONDS)
            _state.value = _state.value.copy(
                busy = false,
                agent = if (ready) AgentState.READY else AgentState.FAILED,
                error = if (ready) null else "the agent did not come back within ${AGENT_TIMEOUT_SECONDS}s"
            )
            logLine(if (ready) "agent restarted" else "agent restart reported a failure")
            onDone(ready)
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

    /**
     * Waits for the guest control API. Bails out the moment the VM process is gone: a QEMU that
     * exited has nothing coming (its forwarding rule could not be bound, its disks were unusable),
     * and waiting out the full timeout only turns a crash into a hang.
     */
    private suspend fun awaitControl(api: VmApiClient, manager: VmManager, timeoutSeconds: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (api.health()) return true
            if (!manager.isRunning()) {
                throw IllegalStateException(
                    "the VM process exited while the guest was starting" +
                        (if (lastVmLine.isNotBlank()) " — last VM line: $lastVmLine" else "")
                )
            }
            delay(1_000)
        }
        return false
    }

    /**
     * The gateway refuses a missing, placeholder or sub-16-character `API_SERVER_KEY` and then exits
     * quietly, leaving nothing on its port, so the key the guest holds has to be one it accepts. Only
     * rewritten when it is actually wrong: the app's own token is a 36-character UUID.
     */
    private fun ensureGuestKey(api: VmApiClient, manager: VmManager) {
        val key = manager.exec("grep -h '^API_SERVER_KEY=' /root/.hermes/.env 2>/dev/null | tail -1 | cut -d= -f2-")
        if (key.length >= 16 && key == manager.token) return
        logLine(if (key.isEmpty()) "no key in the guest — writing the device key" else "the guest key is not the device key — rewriting it")
        val ok = api.configureAgent(
            env = mapOf("API_SERVER_KEY" to manager.token),
            settings = mapOf(
                "platforms.api_server.extra.host" to "0.0.0.0",
                "platforms.api_server.extra.port" to EngineStore.GUEST_AGENT_PORT.toString(),
                "platforms.api_server.extra.key" to manager.token
            )
        )
        if (!ok) logLine("the guest did not confirm the key rewrite — continuing and letting the port decide")
    }

    /**
     * Clear the pidfile the guest trusts, then ask it to start the agent. `start_agent.sh` exits early
     * while the pid in `/var/run/hermes-agent.pid` is alive and guest pids are reused, so a stale file
     * turns every later start into a silent no-op — the file is only removed here when the port is
     * closed, which is the caller's condition for being in this path at all.
     */
    private fun startAgentFromScratch(api: VmApiClient, manager: VmManager) {
        manager.exec("rm -f /var/run/hermes-agent.pid")
        if (!api.startAgent()) logLine("agent start reported a failure — the log below says why")
    }

    /**
     * Waits for the forwarded agent port, reporting progress as it goes: on this hardware the gateway
     * spends minutes enumerating tools and opening its databases before it binds.
     */
    private suspend fun awaitAgentPort(api: VmApiClient, timeoutSeconds: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        var nextReport = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (api.agentApiAnswers()) return true
            if (System.currentTimeMillis() >= nextReport) {
                val waited = timeoutSeconds - ((deadline - System.currentTimeMillis()) / 1000L).toInt()
                logLine("still waiting for the agent on ${EngineStore.localEndpoint()} (${waited}s so far)")
                nextReport = System.currentTimeMillis() + 60_000
            }
            delay(3_000)
        }
        return false
    }

    /** Last lines of the guest's own agent log, for a failure message worth reading. */
    private fun agentLogTail(api: VmApiClient, lines: Int = 6): String =
        api.agentLog(lines * 8)
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeLast(lines)
            .joinToString(" | ")
            .take(500)
            .ifBlank { "(the guest has no agent log yet)" }

    private fun step(message: String) {
        _state.value = _state.value.copy(step = message)
        logLine(message)
    }

    private const val LOG_LIMIT = 400
    private const val CONTROL_TIMEOUT_SECONDS = 600
    // The gateway needs minutes to bind its endpoint on this hardware (tool check_fns, env probe and
    // database setup all run first), so this is the app's patience, not a health check. The same
    // twenty minutes the relay front end waits: a phone is several times slower than a build host.
    private const val AGENT_TIMEOUT_SECONDS = 1200

    /** How long a turn will wait for a cold engine before giving up on it. */
    private const val OPERATIVE_TIMEOUT_SECONDS = 300
}
