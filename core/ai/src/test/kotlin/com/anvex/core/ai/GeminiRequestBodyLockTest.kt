package com.anvex.core.ai

import com.anvex.core.model.MessageRole
import com.anvex.core.model.ProviderConfig
import com.anvex.core.model.ProviderKind
import com.anvex.core.model.ThinkingLevel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A2b REGRESSION LOCK (design note §5.2/§7, the A2a body-lock pattern):
 * adding client tool-calling to the Gemini client must not change the
 * wire shape of a request that carries NO tools — the recorded body
 * stays byte-identical to the pre-A2b serialization. The expected
 * body below was recorded from the UNMODIFIED client, and this test
 * passed against it before the tool-calling change landed.
 */
class GeminiRequestBodyLockTest {

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

    @Test
    fun `no-tools request body is byte-identical to the pre-tool-calling shape`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Hello\"}]},\"finishReason\":\"STOP\"}]," +
                        "\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":1,\"totalTokenCount\":4}}\n\n"
                )
        )
        val client = GeminiClient(config())
        client.streamChat(
            ChatRequest(
                systemPrompt = "s",
                turns = listOf(ChatTurn(MessageRole.USER, "hi")),
                thinkingLevel = ThinkingLevel.NORMAL,
            )
        ).toList()

        val body = server.takeRequest().body.readUtf8()
        assertEquals(
            "{\"systemInstruction\":{\"parts\":[{\"text\":\"s\"}]}," +
                "\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}]," +
                "\"generationConfig\":{}}",
            body,
        )
    }
}
