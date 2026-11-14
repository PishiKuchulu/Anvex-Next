package com.anvex.core.ai

import com.anvex.core.model.MessageRole
import com.anvex.core.model.ProviderConfig
import com.anvex.core.model.ProviderKind
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A2a (design note §5.1–5.2): OpenAI native tool-calling — request
 * rendering (tools array, assistant tool_calls, tool-role messages) and
 * streaming fragment assembly into [StreamEvent.ToolCallReady].
 */
class OpenAiToolCallingTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config() = ProviderRuntimeConfig(
        ProviderConfig(
            id = "p1", name = "test", kind = ProviderKind.OPENAI,
            baseUrl = server.url("/v1").toString(), model = "test-model",
        ),
        "test-key",
    )

    private val readTool = AiToolDefinition(
        name = "read",
        description = "Read a file inside the project workspace.",
        parametersJsonSchema = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
    )

    private fun enqueueSse(body: String) {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
        )
    }

    @Test
    fun `fragmented tool call deltas assemble into one ToolCallReady before Completed`() = runTest {
        enqueueSse(
            "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"pa\"}}]},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"th\\\":\\\"a.txt\\\"}\"}}]},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                "data: [DONE]\n\n"
        )
        val client = OpenAiCompatibleClient(config())
        val events = client.streamChat(
            ChatRequest("s", listOf(ChatTurn(MessageRole.USER, "read a.txt")), tools = listOf(readTool))
        ).toList()

        val ready = events.filterIsInstance<StreamEvent.ToolCallReady>()
        assertEquals(1, ready.size)
        assertEquals(
            listOf(AiToolCall(id = "call_1", name = "read", argumentsJson = "{\"path\":\"a.txt\"}")),
            ready.single().calls,
        )
        // ToolCallReady is emitted BEFORE the terminal Completed.
        assertTrue(events.indexOf(ready.single()) < events.indexOfLast { it is StreamEvent.Completed })
        assertTrue(events.last() is StreamEvent.Completed)
    }

    @Test
    fun `parallel tool calls assemble per index`() = runTest {
        enqueueSse(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[" +
                "{\"index\":0,\"id\":\"c0\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}," +
                "{\"index\":1,\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"bash\",\"arguments\":\"{\\\"command\\\":\"}}]},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"function\":{\"arguments\":\"\\\"ls\\\"}\"}}]},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                "data: [DONE]\n\n"
        )
        val client = OpenAiCompatibleClient(config())
        val events = client.streamChat(
            ChatRequest("s", listOf(ChatTurn(MessageRole.USER, "go")), tools = listOf(readTool))
        ).toList()

        val ready = events.filterIsInstance<StreamEvent.ToolCallReady>().single()
        assertEquals(
            listOf(
                AiToolCall("c0", "read", "{}"),
                AiToolCall("c1", "bash", "{\"command\":\"ls\"}"),
            ),
            ready.calls,
        )
    }

    @Test
    fun `malformed assembled arguments fail with PROTOCOL and emit no call`() = runTest {
        enqueueSse(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"not-json\"}}]},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                "data: [DONE]\n\n"
        )
        val client = OpenAiCompatibleClient(config())
        val events = client.streamChat(
            ChatRequest("s", listOf(ChatTurn(MessageRole.USER, "read")), tools = listOf(readTool))
        ).toList()

        assertTrue(events.none { it is StreamEvent.ToolCallReady })
        assertTrue(events.none { it is StreamEvent.Completed })
        val failed = events.filterIsInstance<StreamEvent.Failed>().single()
        assertEquals(AiError.Kind.PROTOCOL, failed.error.kind)
    }

    @Test
    fun `request renders tools array, tool_choice auto, assistant tool calls and tool results`() = runTest {
        enqueueSse("data: {\"choices\":[{\"delta\":{\"content\":\"done\"}}]}\n\ndata: [DONE]\n\n")
        val client = OpenAiCompatibleClient(config())
        val turns = listOf(
            ChatTurn(MessageRole.USER, "read a.txt"),
            ChatTurn(
                MessageRole.ASSISTANT, "",
                toolCalls = listOf(AiToolCall("call_1", "read", "{\"path\":\"a.txt\"}")),
            ),
            ChatTurn(MessageRole.USER, "file body", toolCallId = "call_1"),
        )
        client.streamChat(ChatRequest("s", turns, tools = listOf(readTool))).toList()

        val body = server.takeRequest().body.readUtf8()
        assertTrue(
            body.contains(
                "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"read\"," +
                    "\"description\":\"Read a file inside the project workspace.\"," +
                    "\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}}}]"
            )
        )
        assertTrue(body.contains("\"tool_choice\":\"auto\""))
        assertTrue(
            body.contains(
                "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\"," +
                    "\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}}]}"
            )
        )
        assertTrue(body.contains("{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"file body\"}"))
    }
}
