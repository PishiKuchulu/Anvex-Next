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
 * A2a REGRESSION LOCK (design note §5.2/§7): adding tool-calling to the
 * OpenAI client must not change the wire shape of a request that carries
 * NO tools — the recorded body stays byte-identical to the pre-A2a
 * serialization. This test uses only pre-A2a types and passed against
 * the unmodified client before the tool-calling change landed.
 */
class OpenAiRequestBodyLockTest {

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

    @Test
    fun `no-tools request body is byte-identical to the pre-tool-calling shape`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\ndata: [DONE]\n\n")
        )
        val client = OpenAiCompatibleClient(config())
        client.streamChat(
            ChatRequest(
                systemPrompt = "s",
                turns = listOf(ChatTurn(MessageRole.USER, "hi")),
                thinkingLevel = ThinkingLevel.NORMAL,
            )
        ).toList()

        val body = server.takeRequest().body.readUtf8()
        assertEquals(
            "{\"model\":\"test-model\",\"messages\":[" +
                "{\"role\":\"system\",\"content\":\"s\"}," +
                "{\"role\":\"user\",\"content\":\"hi\"}]," +
                "\"stream\":true,\"stream_options\":{\"include_usage\":true}," +
                "\"reasoning_effort\":\"medium\"}",
            body,
        )
    }
}
