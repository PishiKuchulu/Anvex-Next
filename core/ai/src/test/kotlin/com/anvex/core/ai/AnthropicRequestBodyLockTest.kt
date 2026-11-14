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
 * adding client tool-calling to the Anthropic client must not change
 * the wire shape of a request that carries NO tools — the recorded
 * body stays byte-identical to the pre-A2b serialization. The expected
 * body below was recorded from the UNMODIFIED client, and this test
 * passed against it before the tool-calling change landed.
 */
class AnthropicRequestBodyLockTest {

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

    @Test
    fun `no-tools request body is byte-identical to the pre-tool-calling shape`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}}\n\n" +
                        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\n" +
                        "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n" +
                        "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
                )
        )
        val client = AnthropicClient(config())
        client.streamChat(
            ChatRequest(
                systemPrompt = "s",
                turns = listOf(ChatTurn(MessageRole.USER, "hi")),
                thinkingLevel = ThinkingLevel.NORMAL,
            )
        ).toList()

        val body = server.takeRequest().body.readUtf8()
        assertEquals(
            "{\"model\":\"test-model\"," +
                "\"system\":[{\"type\":\"text\",\"text\":\"s\",\"cache_control\":{\"type\":\"ephemeral\"}}]," +
                "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]," +
                "\"stream\":true,\"max_tokens\":8192}",
            body,
        )
    }
}
