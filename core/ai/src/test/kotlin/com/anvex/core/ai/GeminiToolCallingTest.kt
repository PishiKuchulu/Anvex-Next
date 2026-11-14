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
 * A2b (design note §5.2 + Q6): Gemini native tool-calling —
 * `functionDeclarations` merged with the existing `google_search`
 * tool entry, whole `functionCall` parts (no fragment assembly),
 * results returning as `functionResponse` parts, and synthesized
 * call ids that are unique per session: the id counter is derived
 * from the number of tool calls already present in the request's
 * conversation, so it is monotonic across the session's turns and
 * cannot reset on resume (the Q6 constraint — a turn index could).
 * Gemini matches `functionResponse` by NAME, so the synthesized id
 * is internal correlation only.
 */
class GeminiToolCallingTest {

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
            id = "p1", name = "test", kind = ProviderKind.GEMINI,
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

    private fun sse(partsJson: String) =
        "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[$partsJson]},\"finishReason\":\"STOP\"}]," +
            "\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":1,\"totalTokenCount\":4}}\n\n"

    @Test
    fun `whole functionCall parts become one ToolCallReady with distinct synthesized ids`() = runTest {
        enqueueSse(
            sse(
                """{"functionCall":{"name":"read","args":{"path":"a.txt"}}},""" +
                    """{"functionCall":{"name":"bash","args":{"command":"ls"}}}"""
            )
        )
        val client = GeminiClient(config())
        val events = client.streamChat(
            ChatRequest("s", listOf(ChatTurn(MessageRole.USER, "read then list")), tools = listOf(readTool))
        ).toList()

        val ready = events.filterIsInstance<StreamEvent.ToolCallReady>()
        assertEquals(1, ready.size)
        assertEquals(
            listOf(
                AiToolCall(id = "gemini-0", name = "read", argumentsJson = """{"path":"a.txt"}"""),
                AiToolCall(id = "gemini-1", name = "bash", argumentsJson = """{"command":"ls"}"""),
            ),
            ready.single().calls,
        )
        assertTrue(events.indexOf(ready.single()) < events.indexOfLast { it is StreamEvent.Completed })
        assertTrue(events.last() is StreamEvent.Completed)
    }

    @Test
    fun `synthesized ids stay unique across turns and results map back by name`() = runTest {
        // Second turn of the session: the conversation already carries
        // the two calls of the first response (gemini-0, gemini-1) and
        // their results, so the next synthesized id must continue the
        // sequence instead of restarting it.
        enqueueSse(sse("""{"functionCall":{"name":"read","args":{"path":"b.txt"}}}"""))
        val priorCalls = listOf(
            AiToolCall("gemini-0", "read", """{"path":"a.txt"}"""),
            AiToolCall("gemini-1", "bash", """{"command":"ls"}"""),
        )
        val client = GeminiClient(config())
        val events = client.streamChat(
            ChatRequest(
                systemPrompt = "s",
                turns = listOf(
                    ChatTurn(MessageRole.USER, "read then list"),
                    ChatTurn(MessageRole.ASSISTANT, "", toolCalls = priorCalls),
                    ChatTurn(MessageRole.USER, "file body", toolCallId = "gemini-0"),
                    ChatTurn(MessageRole.USER, "listing", toolCallId = "gemini-1"),
                    ChatTurn(MessageRole.USER, "now read b.txt"),
                ),
                tools = listOf(readTool),
            )
        ).toList()

        val ready = events.filterIsInstance<StreamEvent.ToolCallReady>()
        assertEquals(1, ready.size)
        assertEquals(
            listOf(AiToolCall(id = "gemini-2", name = "read", argumentsJson = """{"path":"b.txt"}""")),
            ready.single().calls,
        )

        // Request rendering: the model turn carries functionCall parts;
        // the results return as functionResponse parts whose NAME is
        // resolved from the call that produced the id.
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val contents = body["contents"]!!.jsonArray
        val modelParts = contents[1].jsonObject["parts"]!!.jsonArray.map { it.jsonObject }
        assertEquals("model", contents[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("read", "bash"),
            modelParts.map { it["functionCall"]!!.jsonObject["name"]!!.jsonPrimitive.content },
        )
        assertEquals(
            "a.txt",
            modelParts[0]["functionCall"]!!.jsonObject["args"]!!.jsonObject["path"]!!.jsonPrimitive.content,
        )
        val firstResponse = contents[2].jsonObject["parts"]!!.jsonArray.single().jsonObject["functionResponse"]!!.jsonObject
        assertEquals("read", firstResponse["name"]!!.jsonPrimitive.content)
        assertEquals("file body", firstResponse["response"]!!.jsonObject["result"]!!.jsonPrimitive.content)
        val secondResponse = contents[3].jsonObject["parts"]!!.jsonArray.single().jsonObject["functionResponse"]!!.jsonObject
        assertEquals("bash", secondResponse["name"]!!.jsonPrimitive.content)
        assertEquals("listing", secondResponse["response"]!!.jsonObject["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `synthesized ids continue from restored history on resume`() = runTest {
        // Resume: the session was restored with its full history, which
        // re-carries the three calls of the earlier turns (gemini-0..2).
        // The next synthesized id must continue the sequence — this is
        // the Q6 property a turn-index counter could not give, because
        // a resumed session's turn index restarts.
        enqueueSse(sse("""{"functionCall":{"name":"read","args":{"path":"c.txt"}}}"""))
        val client = GeminiClient(config())
        val events = client.streamChat(
            ChatRequest(
                systemPrompt = "s",
                turns = listOf(
                    ChatTurn(MessageRole.USER, "first"),
                    ChatTurn(
                        MessageRole.ASSISTANT, "",
                        toolCalls = listOf(
                            AiToolCall("gemini-0", "read", """{"path":"a.txt"}"""),
                            AiToolCall("gemini-1", "bash", """{"command":"ls"}"""),
                        ),
                    ),
                    ChatTurn(MessageRole.USER, "body", toolCallId = "gemini-0"),
                    ChatTurn(MessageRole.USER, "listing", toolCallId = "gemini-1"),
                    ChatTurn(MessageRole.USER, "second"),
                    ChatTurn(
                        MessageRole.ASSISTANT, "",
                        toolCalls = listOf(AiToolCall("gemini-2", "read", """{"path":"b.txt"}""")),
                    ),
                    ChatTurn(MessageRole.USER, "body b", toolCallId = "gemini-2"),
                    ChatTurn(MessageRole.USER, "resume and read c.txt"),
                ),
                tools = listOf(readTool),
            )
        ).toList()

        val ready = events.filterIsInstance<StreamEvent.ToolCallReady>()
        assertEquals(1, ready.size)
        assertEquals(
            listOf(AiToolCall(id = "gemini-3", name = "read", argumentsJson = """{"path":"c.txt"}""")),
            ready.single().calls,
        )
    }

    @Test
    fun `truncated history shrinks the counter but ids stay distinct per response and results resolve by name`() = runTest {
        // Documented caveat (A3 design note): the id counter derives
        // from the history the client is GIVEN. If a bridge truncates
        // that history, the counter base shrinks with it and a later id
        // can numerically repeat an id whose call was truncated away.
        // That is safe on this wire because Gemini matches
        // functionResponse by NAME and the loop correlates results
        // per turn; what must hold — and is pinned here — is that ids
        // within ONE response are distinct and that a result whose
        // call survives in the retained window still resolves its name.
        enqueueSse(
            sse(
                """{"functionCall":{"name":"read","args":{"path":"x.txt"}}},""" +
                    """{"functionCall":{"name":"bash","args":{"command":"pwd"}}}"""
            )
        )
        val client = GeminiClient(config())
        val events = client.streamChat(
            ChatRequest(
                systemPrompt = "s",
                turns = listOf(
                    ChatTurn(MessageRole.USER, "earlier work"),
                    ChatTurn(
                        MessageRole.ASSISTANT, "",
                        toolCalls = listOf(AiToolCall("gemini-5", "bash", """{"command":"ls"}""")),
                    ),
                    ChatTurn(MessageRole.USER, "old listing", toolCallId = "gemini-5"),
                    ChatTurn(MessageRole.USER, "again"),
                ),
                tools = listOf(readTool),
            )
        ).toList()

        val ready = events.filterIsInstance<StreamEvent.ToolCallReady>()
        assertEquals(1, ready.size)
        assertEquals(listOf("gemini-1", "gemini-2"), ready.single().calls.map { it.id })
        assertEquals(2, ready.single().calls.map { it.id }.distinct().size)

        // The retained result turn still renders functionResponse with
        // the name of its surviving call.
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val contents = body["contents"]!!.jsonArray
        val retained = contents[2].jsonObject["parts"]!!.jsonArray.single()
            .jsonObject["functionResponse"]!!.jsonObject
        assertEquals("bash", retained["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `malformed functionCall arguments fail with PROTOCOL and no ToolCallReady`() = runTest {
        enqueueSse(sse("""{"functionCall":{"name":"read","args":[1,2]}}"""))
        val client = GeminiClient(config())
        val events = client.streamChat(
            ChatRequest("s", listOf(ChatTurn(MessageRole.USER, "read a.txt")), tools = listOf(readTool))
        ).toList()

        assertTrue(events.none { it is StreamEvent.ToolCallReady })
        val failed = events.last() as StreamEvent.Failed
        assertEquals(AiError.Kind.PROTOCOL, failed.error.kind)
    }

    @Test
    fun `functionDeclarations merge with the google_search tool entry`() = runTest {
        enqueueSse(sse("""{"text":"ok"}"""))
        val client = GeminiClient(config())
        client.streamChat(
            ChatRequest(
                systemPrompt = "s",
                turns = listOf(ChatTurn(MessageRole.USER, "hi")),
                webSearch = true,
                tools = listOf(readTool),
            )
        ).toList()

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, tools.size)
        val declarations = tools.first { it.containsKey("functionDeclarations") }["functionDeclarations"]!!.jsonArray
        val read = declarations.single().jsonObject
        assertEquals("read", read["name"]!!.jsonPrimitive.content)
        assertEquals("Read a file inside the project workspace.", read["description"]!!.jsonPrimitive.content)
        assertEquals(
            "string",
            read["parameters"]!!.jsonObject["properties"]!!.jsonObject["path"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        assertTrue(tools.any { it.containsKey("google_search") })
    }
}
