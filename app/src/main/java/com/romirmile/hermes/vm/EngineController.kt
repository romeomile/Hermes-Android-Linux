package com.romirmile.hermes.vm

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * The single authoritative engine startup. Everything that needs a usable engine — the auto-start
     * on launch, a manual Start Engine, a chat turn arriving during boot, a restart — either joins this
     * operation or starts it, and nobody runs a second VM or agent lifecycle beside it.
     *
     * The race this replaces: `awaitOperative` treated "the QEMU process exists" as "the engine is up",
     * so a turn arriving during a boot asked the guest to start the agent while the normal startup was
     * still preparing the guest. The log showed `asking it to start the agent` while the guest's control
     * API was not even answering yet, two `/agent/start` calls were issued, and the second one could
     * disturb the agent the first had just brought up.
     */
    private val lifecycle = Mutex()
    @Volatile
    private var startupTask: Deferred<Boolean>? = null
    @Volatile
    private var startupOwner: String = ""

    /** True while the single authoritative startup operation is in flight. */
    val startupInProgress: Boolean get() = startupTask?.isActive == true

    private var managerInstance: VmManager? = null

    /** Last line QEMU wrote to the serial console, quoted when the VM dies during startup. */
    private var lastVmLine: String = ""

    /**
     * Why the engine is not usable, in the device's own words, so the chat can say what is wrong
     * instead of only that something is. Cleared at the start of each attempt.
     */
    @Volatile
    var lastProblem: String = ""
        private set

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
     * Makes the engine operative and says whether it is.
     *
     * Order of decisions, and nothing else:
     *  1. the agent already answers 2xx on the forwarded port → ready, return immediately;
     *  2. the port answers with an error (a rejected key) → not ready, and say so;
     *  3. a startup operation is already running → **join it**; it is the only thing allowed to start
     *     the VM or the agent, so this call must not touch either;
     *  4. nothing owns the engine → become the owner by launching that same operation, which verifies
     *     the control API and the agent port before deciding anything needs starting.
     *
     * A live QEMU process, a bound guest control API and the guest's own `running` flag are all claims;
     * the port is the only evidence accepted here. [onProgress] feeds the elapsed seconds to the UI.
     */
    suspend fun awaitOperative(context: Context, onProgress: (Int) -> Unit = {}): Boolean {
        val app = context.applicationContext
        val api = manager(app).apiClient
        lastProblem = ""
        val status = api.agentApiStatus(2_000)
        if (status in 200..299) {
            logLine("agent readiness confirmed by HTTP on ${EngineStore.localEndpoint()}")
            return true
        }
        if (status != 0) {
            lastProblem = "the agent answers on ${EngineStore.localEndpoint()} but rejects the device key (HTTP $status)"
            logLine("startup failed: $lastProblem")
            return false
        }

        val running = startupTask
        if (running != null && running.isActive) {
            logLine("startup already in progress — joining existing startup (owner: $startupOwner)")
            runCatching { running.await() }
            if (api.agentApiStatus(2_000) in 200..299) {
                logLine("startup completed successfully (joined)")
                return true
            }
            if (lastProblem.isBlank()) lastProblem = "the engine did not come up"
            logLine("startup failed: $lastProblem")
            return false
        }

        logLine("no startup in progress — this call owns startup")
        val task = launchStartup(app, owner = "chat turn waiting for the engine", force = false, onProgress = onProgress)
        val ready = runCatching { task.await() }.getOrDefault(false)
        if (ready) {
            logLine("startup completed successfully")
        } else {
            logLine("startup failed: ${lastProblem.ifBlank { "the engine did not come up" }}")
        }
        return ready
    }

    /** Waits for an in-flight startup before anything else touches the engine or the agent. */
    private suspend fun joinStartup(reason: String) {
        val running = startupTask ?: return
        if (!running.isActive) return
        logLine("startup already in progress (owner: $startupOwner) — waiting for it before $reason")
        runCatching { running.await() }
    }

    fun token(context: Context): String = EngineStore(context.applicationContext).token

    /**
     * Starts the engine from wherever it currently is: safe to call on launch, from a button, or again
     * after a failure. [force] restarts a VM that is already up.
     *
     * A startup already in flight is **joined, never duplicated**: the launch-time auto-start, a manual
     * Start Engine and a chat turn arriving mid-boot all end up waiting on the same operation, so there
     * is exactly one component starting the agent during a normal boot.
     */
    fun start(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (startupTask?.isActive == true) {
            logLine("startup already in progress — joining existing startup (owner: $startupOwner)")
            return
        }
        launchStartup(app, owner = if (force) "Start Engine (manual)" else "auto-start", force = force)
    }

    /**
     * Launches the single authoritative startup operation. Registers the task before anyone can await
     * it and clears it in `invokeOnCompletion` (not in a coroutine), so a failure, an early return or a
     * cancellation cannot leave the controller believing a startup is still running.
     */
    private fun launchStartup(
        app: Context,
        owner: String,
        force: Boolean,
        onProgress: (Int) -> Unit = {}
    ): Deferred<Boolean> {
        startupOwner = owner
        logLine("startup owner: $owner")
        val task = scope.async { lifecycle.withLock { startupSequence(app, force, owner, onProgress) } }
        task.invokeOnCompletion {
            if (startupTask === task) {
                startupTask = null
                startupOwner = ""
            }
        }
        startupTask = task
        return task
    }

    /**
     * The ordered startup the whole app depends on: VM → control API → guest preparation → agent →
     * HTTP readiness on the forwarded port.
     *
     * It runs under [lifecycle], so exactly one of these exists at a time, and it is exception-safe:
     * every failure is reported through the return value and [lastProblem] rather than thrown at the
     * callers waiting on it, and `busy` is always cleared.
     */
    private suspend fun startupSequence(
        app: Context,
        force: Boolean,
        owner: String,
        onProgress: (Int) -> Unit
    ): Boolean {
        _state.value = _state.value.copy(busy = true, error = null, step = "")
        lastProblem = ""
        return try {
            val manager = manager(app)
            val api = manager.apiClient

            if (manager.isRunning() && !force) {
                logLine("engine already running (owner: $owner) — verifying the guest control API and the agent port")
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

            // The control API is the first real proof: a QEMU process that exists says nothing about
            // whether the guest has booted, and the agent's start must not be requested before this
            // answers.
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
            // `kill -0` on a pidfile and guest pids get reused, so it reports an agent that is alive as
            // a process while nothing is bound to the port. The port the chat itself uses is the only
            // thing accepted here.
            var ready = api.agentApiAnswers()
            if (!ready) {
                logLine("nothing answers on ${EngineStore.localEndpoint()} — this startup owns the agent start")
                step("Starting the agent inside the guest")
                startAgentFromScratch(api, manager)
                ready = awaitAgentPort(api, manager, AGENT_TIMEOUT_SECONDS, onProgress)
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
            logLine("agent readiness confirmed by HTTP on ${EngineStore.localEndpoint()}")
            step("Engine ready")
            true
        } catch (e: Exception) {
            if (lastProblem.isBlank()) lastProblem = e.message ?: "engine start failed"
            logLine("startup failed: ${e.message}")
            // Report the state that is true after the failure instead of leaving the last progress line
            // standing: a screen that says "stopped" and "starting" at once is how this used to read.
            val alive = managerInstance?.status() == "running"
            _state.value = _state.value.copy(
                error = e.message ?: "engine start failed",
                vm = if (alive) "running" else "stopped",
                controlReady = alive && _state.value.controlReady,
                agent = if (alive) _state.value.agent else AgentState.STOPPED
            )
            false
        } finally {
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        // Taking ownership away from a running startup means cancelling it: it releases the lifecycle
        // lock and clears its own busy flag in a finally block, so a cancelled startup never blocks the
        // next one.
        startupTask?.cancel()
        startupTask = null
        startupOwner = ""
        scope.launch {
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
        if (startupTask?.isActive == true) {
            // A guest status read while a startup is mid-flight is stale by definition and would flip a
            // READY display back to STOPPED.
            logLine("refresh skipped — a startup is in progress (owner: $startupOwner)")
            return
        }
        scope.launch {
            val manager = manager(app)
            val vm = manager.status()
            val control = vm == "running" && manager.apiClient.health()
            val agentPort = if (control) manager.apiClient.agentApiStatus(1_500) else 0
            val agent = if (control) manager.apiClient.agentStatus() else null
            _state.value = _state.value.copy(
                vm = vm,
                controlReady = control,
                // The guest's own `running` flag is a pidfile claim; the port the chat uses is evidence.
                agent = when {
                    agentPort in 200..299 -> AgentState.READY
                    control -> AgentState.STOPPED
                    else -> _state.value.agent
                },
                agentVersion = agent?.version ?: _state.value.agentVersion
            )
        }
    }

    /**
     * Restarts the agent inside a running engine.
     *
     * If a startup is still in flight this waits for it first — an explicit restart must never race the
     * boot that is already responsible for starting the agent — and then runs inside the same lifecycle
     * lock, so it cannot overlap another startup either.
     */
    fun restartAgent(context: Context, onDone: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        scope.launch {
            joinStartup("restarting the agent")
            lifecycle.withLock {
                _state.value = _state.value.copy(busy = true, agent = AgentState.STARTING)
                val manager = manager(app)
                val ready = cycleAgent(manager.apiClient, manager)
                _state.value = _state.value.copy(
                    busy = false,
                    agent = if (ready) AgentState.READY else AgentState.FAILED,
                    error = if (ready) null else (lastProblem.ifBlank { "the agent did not come back within ${AGENT_TIMEOUT_SECONDS}s" })
                )
                logLine(if (ready) "agent restarted" else "agent restart reported a failure")
                onDone(ready)
            }
        }
    }

    /**
     * A restart that proves itself, in the order the guest actually works.
     *
     * Signalling the old gateway is not a stop: the guest reports the port while the *dying* process
     * is still bound to it, and the shipped guest's `/agent/start` answers "already running" off a
     * pidfile, so the app could log "agent restarted" while nothing was listening a minute later.
     * Here the port has to go dark first, and the new process has to show a different pid and keep
     * answering before this returns true.
     */
    private suspend fun cycleAgent(
        api: VmApiClient,
        manager: VmManager,
        onProgress: (Int) -> Unit = {}
    ): Boolean {
        lastProblem = ""
        val before = api.agentStatus()?.pid ?: 0
        if (before != 0) logLine("stopping the agent (pid $before)")
        api.stopAgent()
        val stopDeadline = System.currentTimeMillis() + STOP_TIMEOUT_SECONDS * 1000L
        var closed = api.agentApiStatus(2_000) == 0
        val stopStart = System.currentTimeMillis()
        while (!closed && System.currentTimeMillis() < stopDeadline) {
            delay(2_000)
            closed = api.agentApiStatus(2_000) == 0
        }
        if (!closed) {
            lastProblem = "the agent never released port ${EngineStore.GUEST_AGENT_PORT} — it was still answering " +
                "${(System.currentTimeMillis() - stopStart) / 1000L}s after the stop"
            logLine(lastProblem)
            return false
        }
        logLine("agent stopped — the port is closed after ${(System.currentTimeMillis() - stopStart) / 1000L}s")
        return startAgentAndWait(api, manager, previousPid = before, onProgress = onProgress)
    }

    /**
     * Asks the guest to start the agent and waits for evidence: a 2xx on the port the chat uses, from a
     * process that is not the one we just stopped, held across two samples. Failure carries the guest's
     * own agent log, because that is where the reason is written.
     */
    private suspend fun startAgentAndWait(
        api: VmApiClient,
        manager: VmManager,
        previousPid: Int,
        onProgress: (Int) -> Unit = {}
    ): Boolean {
        step("Starting the agent inside the guest")
        startAgentFromScratch(api, manager)
        val start = System.currentTimeMillis()
        val deadline = start + AGENT_TIMEOUT_SECONDS * 1000L
        var nextReport = start + 60_000
        var fresh = 0
        while (System.currentTimeMillis() < deadline) {
            val status = api.agentApiStatus(2_000)
            if (status in 200..299) {
                val pid = api.agentStatus()?.pid ?: 0
                if (pid != 0 && pid == previousPid) {
                    lastProblem = "the port is answering from the agent we just stopped (pid $pid) — the restart did not take"
                    logLine(lastProblem)
                    return false
                }
                fresh++
                if (fresh >= 2) {
                    val waited = (System.currentTimeMillis() - start) / 1000L
                    logLine("agent ready on ${EngineStore.localEndpoint()} (pid $pid, ${waited}s, the port answered)")
                    return true
                }
            } else {
                fresh = 0
                if (status == 401 || status == 403) {
                    lastProblem = "the agent is on the port but rejects the device key (HTTP $status)"
                }
            }
            if (!manager.isRunning()) {
                lastProblem = "the VM process exited while the agent was starting" +
                    (if (lastVmLine.isNotBlank()) " — last VM line: $lastVmLine" else "")
                logLine(lastProblem)
                return false
            }
            val waited = ((System.currentTimeMillis() - start) / 1000L).toInt()
            if (waited != 0 && waited % 5 == 0) onProgress(waited)
            if (System.currentTimeMillis() >= nextReport) {
                logLine("still waiting for the agent on ${EngineStore.localEndpoint()} (${waited}s so far)")
                nextReport = System.currentTimeMillis() + 60_000
            }
            delay(3_000)
        }
        val minutes = AGENT_TIMEOUT_SECONDS / 60
        if (lastProblem.isBlank()) lastProblem = "the agent did not open port ${EngineStore.GUEST_AGENT_PORT} within $minutes minutes"
        logLine(lastProblem)
        logLine("guest agent log: ${agentLogTail(api)}")
        return false
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
            // Writing the agent's configuration needs the guest control API and is followed by an agent
            // restart, so it waits for any startup in flight and then holds the same lock.
            joinStartup("writing the agent configuration")
            lifecycle.withLock {
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
                val ready = cycleAgent(manager(app).apiClient, manager(app))
                _state.value = _state.value.copy(
                    busy = false,
                    agent = if (ready) AgentState.READY else AgentState.FAILED,
                    error = if (ready) null else (lastProblem.ifBlank { "the agent did not come back after the configuration change" })
                )
                logLine(if (ready) "agent restarted with the new model" else "agent restart reported a failure")
                onDone(ok && ready)
            }
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
     * Clear everything that makes the next start a no-op, then ask the guest to start the agent.
     *
     * `/var/run/hermes-agent.pid` makes `start_agent.sh` exit early while that pid is alive (guest pids
     * are reused), and the CLI's own `gateway.lock` makes `hermes gateway run` refuse outright -
     * "Another gateway instance is already running" - which is how a restart ended with a dark port and
     * nothing coming back. Both are only removed here while the port is closed, which is the caller's
     * condition for being in this path at all.
     */
    private fun startAgentFromScratch(api: VmApiClient, manager: VmManager) {
        manager.exec(
            "rm -f /var/run/hermes-agent.pid /root/.hermes/gateway.lock /root/.hermes/gateway.sock"
        )
        if (!api.startAgent()) logLine("agent start reported a failure — the log below says why")
    }

    /**
     * Waits for the forwarded agent port, which is the only readiness this app accepts. Reports the
     * elapsed seconds to the UI every five seconds and logs a line a minute, because on this hardware
     * the gateway spends minutes opening its databases before it binds.
     */
    private suspend fun awaitAgentPort(
        api: VmApiClient,
        manager: VmManager,
        timeoutSeconds: Int,
        onProgress: (Int) -> Unit = {}
    ): Boolean {
        val start = System.currentTimeMillis()
        val deadline = start + timeoutSeconds * 1000L
        var nextLog = start + 60_000
        var reported = -1
        while (System.currentTimeMillis() < deadline) {
            val status = api.agentApiStatus(2_000)
            if (status in 200..299) return true
            if (status != 0) {
                lastProblem = "the agent answers on ${EngineStore.localEndpoint()} but rejects the device key (HTTP $status)"
                logLine(lastProblem)
                return false
            }
            if (!manager.isRunning()) {
                lastProblem = "the VM process exited while the guest was starting" +
                    (if (lastVmLine.isNotBlank()) " — last VM line: $lastVmLine" else "")
                logLine(lastProblem)
                return false
            }
            val waited = ((System.currentTimeMillis() - start) / 1000L).toInt()
            if (waited != reported && waited % 5 == 0) {
                reported = waited
                onProgress(waited)
            }
            if (System.currentTimeMillis() >= nextLog) {
                logLine("still waiting for the agent on ${EngineStore.localEndpoint()} (${waited}s so far)")
                nextLog = System.currentTimeMillis() + 60_000
            }
            delay(2_000)
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

    /** How long the *stop* half of a restart may take: the guest's own wait for the port is 30 s. */
    private const val STOP_TIMEOUT_SECONDS = 90
}
