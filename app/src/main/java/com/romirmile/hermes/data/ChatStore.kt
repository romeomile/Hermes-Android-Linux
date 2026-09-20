package com.romirmile.hermes.data

import android.content.Context
import com.romirmile.hermes.MessageRole
import com.romirmile.hermes.HermesMessage
import com.romirmile.hermes.HermesSession
import com.romirmile.hermes.ToolActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Offline-first chat history: the whole session list (messages included) is kept in
 * a single JSON file in the app's private storage. No database, no server state —
 * every turn is sent to the gateway with the full local transcript (stateless chat
 * Hermes chat completions).
 */
object ChatStore {

    private const val FILE_NAME = "chats.json"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun load(context: Context): List<HermesSession> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("sessions") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i -> parseSession(arr.optJSONObject(i)) }
                .sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, sessions: List<HermesSession>) {
        runCatching {
            val root = JSONObject()
            val arr = JSONArray()
            sessions.forEach { arr.put(sessionJson(it)) }
            root.put("sessions", arr)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(file(context))
        }
    }

    private fun sessionJson(s: HermesSession): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("title", s.title)
        put("createdAt", s.createdAt)
        put("updatedAt", s.updatedAt)
        put("gatewaySessionId", s.gatewaySessionId)
        val msgs = JSONArray()
        s.messages.forEach { m ->
            msgs.put(JSONObject().apply {
                put("id", m.id)
                put("role", m.role.name)
                put("text", m.text)
                put("timestamp", m.timestamp)
                put("error", m.error)
                if (m.imagePath != null) put("imagePath", m.imagePath)
                if (m.activity.isNotEmpty()) {
                    val acts = JSONArray()
                    m.activity.forEach { a ->
                        acts.put(JSONObject().apply {
                            put("id", a.id)
                            put("tool", a.tool)
                            put("preview", a.preview)
                            put("error", a.error)
                            a.duration?.let { put("duration", it) }
                        })
                    }
                    put("activity", acts)
                }
            })
        }
        put("messages", msgs)
    }

    private fun parseSession(o: JSONObject?): HermesSession? {
        if (o == null) return null
        val id = o.optString("id", "")
        if (id.isBlank()) return null
        val msgs = mutableListOf<HermesMessage>()
        val arr = o.optJSONArray("messages")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val role = runCatching { MessageRole.valueOf(m.optString("role", "USER")) }
                    .getOrDefault(MessageRole.USER)
                val acts = mutableListOf<ToolActivity>()
                val aArr = m.optJSONArray("activity")
                if (aArr != null) {
                    for (j in 0 until aArr.length()) {
                        val a = aArr.optJSONObject(j) ?: continue
                        acts += ToolActivity(
                            id = a.optString("id", "$j"),
                            tool = a.optString("tool", ""),
                            preview = a.optString("preview", ""),
                            running = false,
                            error = a.optBoolean("error", false),
                            duration = if (a.has("duration")) a.optDouble("duration") else null
                        )
                    }
                }
                msgs += HermesMessage(
                    id = m.optString("id", "$i"),
                    sessionId = id,
                    role = role,
                    text = m.optString("text", ""),
                    timestamp = m.optLong("timestamp", 0L),
                    streaming = false,
                    error = m.optBoolean("error", false),
                    activity = acts,
                    imagePath = m.optString("imagePath").takeIf { it.isNotBlank() }
                )
            }
        }
        return HermesSession(
            id = id,
            title = o.optString("title", "New chat"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            gatewaySessionId = o.optString("gatewaySessionId", ""),
            messages = msgs
        )
    }
}
