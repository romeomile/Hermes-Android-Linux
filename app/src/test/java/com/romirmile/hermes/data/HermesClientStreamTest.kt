package com.romirmile.hermes.data

import com.romirmile.hermes.ChatTurn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Replays streams recorded from a real Hermes 0.21.3 gateway (the guest image the app ships) through
 * the app's own client, over real HTTP. The fixtures in `src/test/resources/streams` are verbatim:
 *
 *  - `tool_turn.sse`   — a turn that ran the `terminal` tool, then answered
 *  - `agent_error.sse` — a turn whose provider call failed (HTTP 200, finish_reason "error")
 *  - `probe_ok.json` / `probe_error.json` — the same two outcomes, non-streaming
 */
class HermesClientStreamTest {

    // A minimal one-response-per-connection HTTP server: unit tests compile against android.jar, which
    // has no com.sun.net.httpserver, and a raw socket is all an HttpURLConnection client needs.
    private lateinit var server: ServerSocket
    @Volatile private var body: String = ""
    @Volatile private var contentType: String = "text/event-stream"
    private val base get() = "http://127.0.0.1:${server.localPort}"

    @Before
    fun start() {
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { answer(socket) }
                runCatching { socket.close() }
            }
        }
    }

    private fun answer(socket: Socket) {
        val input = socket.getInputStream()
        // Request line and headers, up to the blank line; then the body by Content-Length.
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return
            head.append(b.toChar())
        }
        val length = Regex("(?im)^content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
        var remaining = length
        while (remaining > 0) {
            val skipped = input.read(ByteArray(remaining))
            if (skipped < 0) break
            remaining -= skipped
        }
        val bytes = body.toByteArray()
        val out = socket.getOutputStream()
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
    }

    @After
    fun stop() = server.close()

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("streams/$name")!!.readText()

    private fun stream(): List<StreamEvent> = runBlocking {
        HermesClient().streamChat(
            baseUrl = base,
            apiKey = "test-key-0123456789",
            model = "hermes-agent",
            turns = listOf(ChatTurn(role = "user", text = "hi"))
        ).toList()
    }

    @Test
    fun toolTurnStartsThenFinishesTheToolAndStreamsTheAnswer() {
        body = fixture("tool_turn.sse")
        val events = stream()

        val started = events.filterIsInstance<StreamEvent.ToolStarted>()
        val finished = events.filterIsInstance<StreamEvent.ToolFinished>()
        assertEquals(listOf("terminal"), started.map { it.tool })
        assertEquals("echo hi", started.single().preview)
        assertEquals(listOf("terminal"), finished.map { it.tool })
        assertFalse(finished.single().error)

        val text = events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text }
        assertEquals("stub answer to: please TOOL run", text.trim())
        assertTrue(events.none { it is StreamEvent.Failed })
    }

    @Test
    fun agentErrorInsideA200StreamIsReportedNotSwallowed() {
        body = fixture("agent_error.sse")
        val events = stream()

        val failure = events.filterIsInstance<StreamEvent.Failed>().single()
        assertEquals(FailureKind.AGENT, failure.kind)
        assertEquals("Connection error.", failure.detail)
        assertFalse(failure.retryable)
        assertTrue(events.none { it is StreamEvent.Delta })
    }

    @Test
    fun probeReportsAWorkingModel() {
        contentType = "application/json"
        body = fixture("probe_ok.json")
        val probe = runBlocking { HermesClient().probeChat(base, "k", "hermes-agent") }.getOrThrow()
        assertEquals("stub answer to: hello", probe.reply)
    }

    @Test
    fun probeDoesNotCallAFailedProviderCallOk() {
        contentType = "application/json"
        body = fixture("probe_error.json")
        val result = runBlocking { HermesClient().probeChat(base, "k", "hermes-agent") }
        val error = result.exceptionOrNull()
        assertTrue("a failed provider call was reported as working", error is HermesClient.AgentReplyException)
        assertEquals("Connection error.", error?.message)
    }
}
