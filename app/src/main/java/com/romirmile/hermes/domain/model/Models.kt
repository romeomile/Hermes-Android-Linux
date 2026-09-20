package com.romirmile.hermes

enum class MessageRole { USER, ASSISTANT, SYSTEM }

data class ToolActivity(
    val id: String,
    val tool: String,
    val preview: String = "",
    val running: Boolean = true,
    val error: Boolean = false,
    val duration: Double? = null
)

data class HermesMessage(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val text: String,
    val timestamp: Long,
    val streaming: Boolean = false,
    val error: Boolean = false,
    val activity: List<ToolActivity> = emptyList(),
    /** Local file path of an image attached to a user message (for display only). */
    val imagePath: String? = null
)

data class HermesSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * Gateway-side Hermes session this chat belongs to. Sent as `X-Hermes-Session-Id`, so every
     * chat is a real Hermes session (visible in the gateway's session list) and a new chat starts
     * a fresh one.
     */
    val gatewaySessionId: String = "",
    val messages: List<HermesMessage> = emptyList()
)

/** An image staged in the composer, ready to be sent with the next turn. */
data class PendingImage(
    val path: String,
    val dataUrl: String
)

/** One outbound turn: text plus an optional inline image (Hermes content-part format). */
data class ChatTurn(
    val role: String,
    val text: String,
    val imageDataUrl: String? = null
)
