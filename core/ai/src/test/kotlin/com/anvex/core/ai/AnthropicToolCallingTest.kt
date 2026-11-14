package com.anvex.core.ai

import com.anvex.core.model.MessageRole
import com.anvex.core.model.ProviderConfig
import com.anvex.core.model.ProviderKind
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A2b (design note §5.2): Anthropic native tool-calling — request
 * rendering (tools array coexisting with the web_search server tool,
 * assistant tool_use blocks, user tool_result blocks) and streaming
 * `input_json_delta` assembly into [StreamEvent.ToolCallReady].
 */
class AnthropicToolCallingTest {

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
            id = "p1", name = "test", kind = ProviderKind.ANTHROPIC,
            baseUrl = server.url("/").toString(), model = "test-model",
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

    private fun frame(event: String, data: String) = "event: $event\ndata: $data\n\n"

    @Test
    fun `fragmented input_json_delta across frames assembles into one ToolCallReady before Completed`() = runTest {
        enqueueSse(
            frame("message_start", """{"type":"message_start","message":{"id":"m1","usage":{"input_tokens":5,"output_tokens":1}}}""") +
                frame("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"read","input":{}}}""") +
                frame("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"pa"}}""") +
                frame("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"th\":\"a.txt\"}"}}""") +
                frame("content_block_stop", """{"type":"content_block_stop","index":0}""") +
                frame("message_delta", """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":9}}""") +
                frame("message_stop", """{"type":"message_stop"}""")
        )
        val client = AnthropicClient(config())
        val events = client.streamChat(
            ChatRequest("s", listOf(ChatTurn(MessageRole.USER, "read a.txt")), tools = listOf(readTool))
        ).toList()

        val ready = events.filterIsInstance<StreamEvent.ToolCallReady>()
        assertEquals(1, ready.size)
        assertEquals(
            listOf(AiToolCall(id = "toolu_1", name = "read", argumentsJson = """{"path":"a.txt"}""")),
            ready.single().calls,
        )
        assertTrue(events.indexOf(ready.single()) < events.indexOfLast { it is StreamEvent.Completed })
        assertTrue(events.last() is StreamEvent.Completed)
        assertEquals("tool_use", (events.last() as StreamEvent.Completed).finishReason)
    }

    @Test
    fun `two tool_use blocks are served in block order`() = runTest {
        enqueueSse(
            frame("message_start", """{"type":"message_start","message":{"id":"m1","usage":{"input_tokens":5,"output_tokens":1}}}""") +
                frame("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"read","input":{}}}""") +
                frame("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a.txt\"}"}}""") +
                frame("content_block_stop", """{"type":"content_block_stop","index":0}""") +
                frame("content_block_start", """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_2","name":"bash","input":{}}}""") +
                frame("content_block_delta", """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"command\":\"ls\"}"}}""") +
                frame("content_block_stop", """{"type":"content_block_stop","index":1}""") +
                frame("message_delta", """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":9}}""") +
                frame("message_stop", """{"type":"message_stop"}""")
        )
        val client = AnthropicClient(config())
        val events = client.streamChat(
            ChatRequest("s", listOf(ChatTurn(MessageRole.USER, "read then list")), tools = listOf(readTool))
        ).toList()

        val ready = events.filterIsInstance<StreamEvent.ToolCallReady>()
        assertEquals(1, ready.size)
        assertEquals(
            listOf(
                AiToolCall(id = "toolu_1", name = "read", argumentsJson = """{"path":"a.txt"}"""),
                AiToolCall(id = "toolu_2", name = "bash", argumentsJson = """{"command":"ls"}"""),
            ),
            ready.single().calls,
        )
    }

    @Test
    fun `malformed assembled arguments fail with PROTOCOL and no ToolCallReady`() = runTest {
        enqueueSse(
            frame("message_start", """{"type":"message_start","message":{"id":"m1","usage":{"input_tokens":5,"output_tokens":1}}}""") +
                frame("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"read","input":{}}}""") +
                frame("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":"}}""") +
                frame("content_block_stop", """{"type":"content_block_stop","index":0}""") +
                frame("message_delta", """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":9}}""") +
                frame("message_stop", """{"type":"message_stop"}""")
        )
        val client = AnthropicClient(config())
        val events = client.streamChat(
            ChatRequest("s", listOf(ChatTurn(MessageRole.USER, "read a.txt")), tools = listOf(readTool))
        ).toList()

        assertTrue(events.none { it is StreamEvent.ToolCallReady })
        val failed = events.last() as StreamEvent.Failed
        assertEquals(AiError.Kind.PROTOCOL, failed.error.kind)
    }

    @Test
    fun `request renders client tools beside web_search plus tool_use and tool_result turns`() = runTest {
        enqueueSse(
            frame("message_start", """{"type":"message_start","message":{"id":"m1","usage":{"input_tokens":5,"output_tokens":1}}}""") +
                frame("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"done"}}""") +
                frame("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""") +
                frame("message_stop", """{"type":"message_stop"}""")
        )
        val call = AiToolCall("toolu_1", "read", """{"path":"a.txt"}""")
        val client = AnthropicClient(config())
        client.streamChat(
            ChatRequest(
                systemPrompt = "s",
                turns = listOf(
                    ChatTurn(MessageRole.USER, "read a.txt"),
                    ChatTurn(MessageRole.ASSISTANT, "", toolCalls = listOf(call)),
                    ChatTurn(MessageRole.USER, "file body", toolCallId = "toolu_1"),
                    ChatTurn(MessageRole.USER, "thanks"),
                ),
                webSearch = true,
                tools = listOf(readTool),
            )
        ).toList()

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        // Client tools and the web_search server tool coexist in one array.
        val tools = body["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        val clientTool = tools.map { it.jsonObject }.first { it["name"]?.jsonPrimitive?.content == "read" }
        assertEquals("Read a file inside the project workspace.", clientTool["description"]!!.jsonPrimitive.content)
        assertEquals(
            "string",
            clientTool["input_schema"]!!.jsonObject["properties"]!!.jsonObject["path"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "path",
            clientTool["input_schema"]!!.jsonObject["required"]!!.jsonArray.single().jsonPrimitive.content,
        )
        assertTrue(tools.map { it.jsonObject }.any { it["type"]?.jsonPrimitive?.content == "web_search_20250305" })

        val messages = body["messages"]!!.jsonArray
        // Assistant turn renders a tool_use block carrying the call.
        val assistant = messages[1].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val useBlock = assistant["content"]!!.jsonArray.map { it.jsonObject }.single { it["type"]?.jsonPrimitive?.content == "tool_use" }
        assertEquals("toolu_1", useBlock["id"]!!.jsonPrimitive.content)
        assertEquals("read", useBlock["name"]!!.jsonPrimitive.content)
        assertEquals("a.txt", useBlock["input"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        // The result returns as a tool_result block inside a user turn.
        val resultTurn = messages[2].jsonObject
        assertEquals("user", resultTurn["role"]!!.jsonPrimitive.content)
        val resultBlock = resultTurn["content"]!!.jsonArray.map { it.jsonObject }.single()
        assertEquals("tool_result", resultBlock["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", resultBlock["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("file body", resultBlock["content"]!!.jsonPrimitive.content)
    }
}
