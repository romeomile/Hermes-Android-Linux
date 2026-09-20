package com.romirmile.hermes.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Agent administration that is not part of the chat contract: the scheduled jobs and the installed
 * skills, with their controls.
 *
 * Endpoints verified against the api_server route table (`APIServerAdapter._http_route_table`), not
 * guessed:
 *
 *   GET    /api/jobs                  -> {"jobs": [{job_id, name, state, schedule, repeat,
 *                                                  next_run_at, last_run_at, last_status,
 *                                                  skills, prompt_preview}]}
 *   POST   /api/jobs/{id}/run         -> run once now
 *   POST   /api/jobs/{id}/pause       / resume
 *   DELETE /api/jobs/{id}             -> remove the job
 *   GET    /v1/skills                 -> {"object": "list", "data": [{name, description, category}]}
 */
object GatewayAdmin {

    /** Admin call failed (endpoint missing on this agent build, auth, or a gateway error). */
    class AdminException(message: String) : Exception(message)

    data class Job(
        val id: String,
        val name: String,
        val state: String,
        val schedule: String,
        val repeatLabel: String? = null,
        val nextRunAt: String? = null,
        val lastRunAt: String? = null,
        val lastStatus: String? = null,
        val lastError: String? = null,
        val prompt: String? = null,
        val enabled: Boolean = true
    ) {
        val paused: Boolean
            get() = !enabled || state.contains("pause", true) || state.contains("disable", true)
    }

    data class Skill(val name: String, val description: String, val category: String? = null)

    fun jobs(baseUrl: String, apiKey: String): List<Job> {
        val body = request("GET", "${root(baseUrl)}/api/jobs?include_disabled=true", apiKey, null)
        val items = JSONObject(body).optJSONArray("jobs") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            // The API answers `id`; older builds used `job_id`.
            val id = item.optString("id").ifBlank { item.optString("job_id") }
            if (id.isBlank()) return@mapNotNull null
            Job(
                id = id,
                name = item.optString("name").ifBlank { id },
                state = item.optString("state").ifBlank { "?" },
                // `schedule` is an object ({kind, expr, display}); `schedule_display` is the flat form.
                schedule = item.optString("schedule_display").ifBlank {
                    item.optJSONObject("schedule")?.optString("display").orEmpty().ifBlank {
                        item.optJSONObject("schedule")?.optString("expr").orEmpty()
                    }
                },
                repeatLabel = repeatLabel(item.optJSONObject("repeat")),
                nextRunAt = item.optString("next_run_at").ifBlank { null },
                lastRunAt = item.optString("last_run_at").ifBlank { null },
                lastStatus = item.optString("last_status").ifBlank { null },
                lastError = item.optString("last_error").ifBlank { null },
                prompt = item.optString("prompt").ifBlank { null },
                enabled = item.optBoolean("enabled", true)
            )
        }
    }

    /** "3× (2 done)" from `repeat: {times, completed}` — null when the job is unbounded and unrun. */
    private fun repeatLabel(repeat: JSONObject?): String? {
        if (repeat == null) return null
        val times = if (repeat.isNull("times")) null else repeat.optInt("times")
        val completed = repeat.optInt("completed", 0)
        return when {
            times != null -> "$times× ($completed done)"
            completed > 0 -> "$completed run(s)"
            else -> null
        }
    }

    fun runJob(baseUrl: String, apiKey: String, id: String) = post(baseUrl, apiKey, "/api/jobs/$id/run")

    fun pauseJob(baseUrl: String, apiKey: String, id: String) = post(baseUrl, apiKey, "/api/jobs/$id/pause")

    fun resumeJob(baseUrl: String, apiKey: String, id: String) = post(baseUrl, apiKey, "/api/jobs/$id/resume")

    fun deleteJob(baseUrl: String, apiKey: String, id: String) {
        request("DELETE", "${root(baseUrl)}/api/jobs/$id", apiKey, null)
    }

    fun skills(baseUrl: String, apiKey: String): List<Skill> {
        val body = request("GET", "${root(baseUrl)}/v1/skills", apiKey, null)
        val items = JSONObject(body).optJSONArray("data") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name")
            if (name.isBlank()) return@mapNotNull null
            Skill(
                name = name,
                description = item.optString("description"),
                category = item.optString("category").ifBlank { null }
            )
        }
    }

    private fun post(baseUrl: String, apiKey: String, path: String) {
        request("POST", root(baseUrl) + path, apiKey, "{}")
    }

    private fun root(baseUrl: String): String =
        baseUrl.trim().trimEnd('/').removeSuffix("/v1")

    private fun request(method: String, url: String, apiKey: String, body: String?): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { input ->
                val out = ByteArrayOutputStream()
                input.copyTo(out)
                out.toString(Charsets.UTF_8.name())
            }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
                throw AdminException(
                    "HTTP $code" + if (detail.isBlank()) "" else ": ${detail.take(160)}"
                )
            }
            return text
        } catch (t: javax.net.ssl.SSLException) {
            throw AdminException("Connection failed: ${t.message}")
        } finally {
            conn.disconnect()
        }
    }
}
