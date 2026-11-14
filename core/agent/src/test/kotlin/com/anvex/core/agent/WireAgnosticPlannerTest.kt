package com.anvex.core.agent

import com.anvex.core.ai.AiToolCall
import com.anvex.core.ai.ChatRequest
import com.anvex.core.ai.ProviderClient
import com.anvex.core.ai.StreamEvent
import com.anvex.core.ai.ValidationResult
import com.anvex.core.model.ModelInfo
import com.anvex.core.model.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A2b (design note §5.1/Q7): ONE planner drives every wire format.
 * The fake clients below replay the exact [StreamEvent] sequences
 * the real Anthropic and Gemini clients emit for the same logical
 * exchange (the clients assemble their wire fragments internally
 * and surface one [StreamEvent.ToolCallReady]; the ids are
 * provider-shaped — Anthropic's `toolu_…` vs Gemini's synthesized
 * `gemini-…`). The planner must produce the SAME action sequence
 * for both, treating the call — id included — as opaque data, and
 * must feed the result back in the same wire-neutral turn shape.
 */
class WireAgnosticPlannerTest {

    private val context = TurnContext(sessionId = "s1", projectId = "p1", providerId = "prov", modelId = "m1")

    private class FakeClient(
        override val kind: ProviderKind,
        private val scripts: List<List<StreamEvent>>,
    ) : ProviderClient {
        val requests = mutableListOf<ChatRequest>()
        private var index = 0
        override fun streamChat(request: ChatRequest): Flow<StreamEvent> {
            requests += request
            val script = scripts[index++]
            return flow { script.forEach { emit(it) } }
        }
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override suspend fun validateConnection(): ValidationResult = ValidationResult(true, 0, "ok")
    }

    /** The shared logical exchange: one read call, then a text answer. */
    private fun scriptFor(call: AiToolCall, finishReason: String) = listOf(
        listOf(StreamEvent.ToolCallReady(listOf(call)), StreamEvent.Completed(finishReason)),
        listOf(StreamEvent.Token("file contents"), StreamEvent.Completed("stop")),
    )

    private fun runExchange(client: FakeClient, call: AiToolCall): List<PlannedAction> = runBlocking {
        val planner = NativeToolCallingPlanner(client, systemPrompt = "sys", prompt = "read a.txt")
        val actions = mutableListOf<PlannedAction>()
        actions += planner.nextAction(context, emptyList(), CoreTools.ALL)
        val history = listOf(
            AgentEvent.TurnStarted("t1"),
            AgentEvent.ToolCallProposed(ToolCall(call.id, call.name, call.argumentsJson)),
            AgentEvent.ToolResultReceived(ToolResult(call.id, call.name, "file body")),
        )
        actions += planner.nextAction(context, history, CoreTools.ALL)
        actions
    }

    @Test
    fun `one planner drives anthropic-shaped and gemini-shaped clients with the same action sequence`() {
        val anthropicCall = AiToolCall("toolu_1", "read", """{"path":"a.txt"}""")
        val anthropicClient = FakeClient(ProviderKind.ANTHROPIC, scriptFor(anthropicCall, "tool_use"))
        val anthropicActions = runExchange(anthropicClient, anthropicCall)

        val geminiCall = AiToolCall("gemini-0", "read", """{"path":"a.txt"}""")
        val geminiClient = FakeClient(ProviderKind.GEMINI, scriptFor(geminiCall, "STOP"))
        val geminiActions = runExchange(geminiClient, geminiCall)

        // Same sequence; only the provider-owned call id differs, and
        // it passes through opaquely in both directions.
        assertEquals(
            listOf(
                PlannedAction.CallTool(ToolCall("toolu_1", "read", """{"path":"a.txt"}""")),
                PlannedAction.Respond("file contents"),
            ),
            anthropicActions,
        )
        assertEquals(
            listOf(
                PlannedAction.CallTool(ToolCall("gemini-0", "read", """{"path":"a.txt"}""")),
                PlannedAction.Respond("file contents"),
            ),
            geminiActions,
        )

        // Both second requests carry the identical wire-neutral shape:
        // the four tool definitions, the assistant tool-call turn and
        // the tool-result turn keyed by the provider's own id.
        for ((client, call) in listOf(anthropicClient to anthropicCall, geminiClient to geminiCall)) {
            val secondRequest = client.requests[1]
            assertEquals(listOf("read", "write", "edit", "bash"), secondRequest.tools.map { it.name })
            assertEquals(listOf(call), secondRequest.turns.first { it.toolCalls.isNotEmpty() }.toolCalls)
            val resultTurn = secondRequest.turns.first { it.toolCallId == call.id }
            assertEquals("file body", resultTurn.content)
        }
    }
}
