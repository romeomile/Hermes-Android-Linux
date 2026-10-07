package com.romirmile.hermes.vm

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the control API that runs inside the guest (see `image/guest/api_server.py`).
 *
 * QEMU forwards the device's loopback port to the guest, so this is ordinary HTTP against
 * `127.0.0.1`. Every call carries the device-generated token. Kept on HttpURLConnection on
 * purpose: the app already talks to the gateway that way, so no HTTP dependency is added.
 */
class VmApiClient(private val token: String) {

    data class AgentStatus(
        val installed: Boolean = false,
        val running: Boolean = false,
        val version: String = "",
        val pid: Int = 0
    )

    data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int, val timedOut: Boolean)

    private val base = "http://127.0.0.1:${EngineStore.CONTROL_PORT}"

    /** The control API answers without auth, so this works before the agent is up. */
    fun health(): Boolean = try {
        val body = request("GET", "/health", null, 8_000)
        JSONObject(body).optString("status") == "ok"
    } catch (_: Exception) {
        false
    }

    /**
     * True when the agent's API port answers at all — any HTTP status counts, because even a 401 means
     * something is listening there. This, and not the guest's own "running" flag, is the readiness
     * evidence: that flag is `kill -0` on a pidfile and guest pids are reused, so it can read true
     * while nothing is bound to the port.
     */
    fun agentApiAnswers(timeoutMs: Int = 5_000): Boolean = try {
        val connection =
            (URL("${EngineStore.localEndpoint()}/v1/models").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Authorization", "Bearer $token")
            }
        connection.responseCode
        true
    } catch (_: Exception) {
        false
    }

    fun agentStatus(): AgentStatus? = try {
        val json = JSONObject(request("GET", "/agent/status", null, 120_000))
        AgentStatus(
            installed = json.optBoolean("installed"),
            running = json.optBoolean("running"),
            version = json.optString("version"),
            pid = json.optInt("pid")
        )
    } catch (_: Exception) {
        null
    }

    /** Starts the agent inside the guest; the guest-side script is idempotent. */
    fun startAgent(): Boolean = try {
        JSONObject(request("POST", "/agent/start", "{}", 300_000)).optBoolean("ok")
    } catch (_: Exception) {
        false
    }

    fun stopAgent(): Boolean = try {
        JSONObject(request("POST", "/agent/stop", "{}", 120_000)).optBoolean("ok")
    } catch (_: Exception) {
        false
    }

    fun agentLog(lines: Int = 200): String = try {
        JSONObject(request("GET", "/agent/log?lines=$lines", null, 60_000)).optString("log")
    } catch (_: Exception) {
        ""
    }

    fun vmExec(cmd: String, timeoutSeconds: Int = 60): ExecResult {
        val payload = JSONObject().put("cmd", cmd).put("timeout", timeoutSeconds).toString()
        val json = JSONObject(request("POST", "/vm/exec", payload, (timeoutSeconds + 30) * 1_000L))
        return ExecResult(
            stdout = json.optString("stdout"),
            stderr = json.optString("stderr"),
            exitCode = json.optInt("exitCode"),
            timedOut = json.optBoolean("timedOut")
        )
    }

    /**
     * Writes the agent's credentials and behavioural settings. [env] entries land in the agent's
     * `.env` (provider credentials), [settings] are dotted config keys such as
     * `model.provider`.
     */
    fun configureAgent(env: Map<String, String>, settings: Map<String, String>): Boolean {
        val envJson = JSONArray()
        env.forEach { (name, value) ->
            if (name.isNotBlank()) envJson.put(JSONObject().put("name", name).put("value", value))
        }
        val settingsJson = JSONObject()
        settings.forEach { (key, value) -> settingsJson.put(key, value) }
        val payload = JSONObject().put("env", envJson).put("settings", settingsJson).toString()
        return try {
            JSONObject(request("POST", "/agent/config", payload, 300_000)).optBoolean("ok")
        } catch (_: Exception) {
            false
        }
    }

    private fun request(method: String, path: String, body: String?, timeoutMs: Long): String {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = timeoutMs.toInt()
            setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code: $text")
            return text
        } finally {
            connection.disconnect()
        }
    }
}
