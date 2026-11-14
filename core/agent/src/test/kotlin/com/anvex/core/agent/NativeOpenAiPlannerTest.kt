package com.anvex.core.agent

import com.anvex.core.ai.AiError
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A2a (§5.2/§7): the native OpenAI planner — ToolCallReady → CallTool,
 * parallel calls served sequentially from the queue, tool results fed
 * back as tool turns, provider failure surfaced as ProviderCallException.
 */
class NativeOpenAiPlannerTest {

    private val context = TurnContext(sessionId = "s1", projectId = "p1", providerId = "prov", modelId = "m1")

    private class FakeClient(private val scripts: List<List<StreamEvent>>) : ProviderClient {
        val requests = mutableListOf<ChatRequest>()
        private var index = 0
        override val kind = ProviderKind.OPENAI
        override fun streamChat(request: ChatRequest): Flow<StreamEvent> {
            requests += request
            val script = scripts[index++]
            return flow { script.forEach { emit(it) } }
        }
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override suspend fun validateConnection(): ValidationResult = ValidationResult(true, 0, "ok")
    }

    @Test fun toolCallReadyBecomesCallToolAndResultIsFedBackAsToolTurn() = runBlocking {
        val call = AiToolCall("call_1", "read", """{"path":"a.txt"}""")
        val client = FakeClient(
            listOf(
                listOf(StreamEvent.ToolCallReady(listOf(call)), StreamEvent.Completed("tool_calls")),
                listOf(StreamEvent.Token("file contents"), StreamEvent.Completed("stop")),
            )
        )
        val planner = NativeOpenAiPlanner(client, systemPrompt = "sys", prompt = "read a.txt")

        val first = planner.nextAction(context, emptyList(), CoreTools.ALL)
        assertEquals(PlannedAction.CallTool(ToolCall("call_1", "read", """{"path":"a.txt"}""")), first)

        val history = listOf(
            AgentEvent.TurnStarted("t1"),
            AgentEvent.ToolCallProposed(ToolCall("call_1", "read", """{"path":"a.txt"}""")),
            AgentEvent.ToolResultReceived(ToolResult("call_1", "read", "file body")),
        )
        val second = planner.nextAction(context, history, CoreTools.ALL)
        assertEquals(PlannedAction.Respond("file contents"), second)

        // The second request carries the tool definition, the assistant
        // tool-call turn and the tool-result turn.
        val secondRequest = client.requests[1]
        assertEquals(listOf("read", "write", "edit", "bash"), secondRequest.tools.map { it.name })
        val assistantTurn = secondRequest.turns.first { it.toolCalls.isNotEmpty() }
        assertEquals(listOf(call), assistantTurn.toolCalls)
        val toolTurn = secondRequest.turns.first { it.toolCallId == "call_1" }
        assertEquals("file body", toolTurn.content)
    }

    @Test fun parallelCallsAreServedSequentiallyWithoutASecondRequest() = runBlocking {
        val calls = listOf(
            AiToolCall("c0", "read", "{}"),
            AiToolCall("c1", "bash", """{"command":"ls"}"""),
        )
        val client = FakeClient(listOf(listOf(StreamEvent.ToolCallReady(calls), StreamEvent.Completed("tool_calls"))))
        val planner = NativeOpenAiPlanner(client, systemPrompt = "sys", prompt = "go")

        assertEquals(PlannedAction.CallTool(ToolCall("c0", "read", "{}")), planner.nextAction(context, emptyList(), CoreTools.ALL))
        assertEquals(
            PlannedAction.CallTool(ToolCall("c1", "bash", """{"command":"ls"}""")),
            planner.nextAction(context, emptyList(), CoreTools.ALL),
        )
        assertEquals(1, client.requests.size)
    }

    @Test fun providerFailureThrowsProviderCallException() = runBlocking {
        val cause = AiError(AiError.Kind.RATE_LIMIT, "slow down")
        val client = FakeClient(listOf(listOf(StreamEvent.Failed(cause))))
        val planner = NativeOpenAiPlanner(client, systemPrompt = "sys", prompt = "hi")

        try {
            planner.nextAction(context, emptyList(), CoreTools.ALL)
            fail("expected ProviderCallException")
        } catch (e: ProviderCallException) {
            assertEquals(cause, e.error)
        }
    }

    @Test fun plainTextStreamBecomesRespond() = runBlocking {
        val client = FakeClient(
            listOf(listOf(StreamEvent.Token("Hel"), StreamEvent.Token("lo"), StreamEvent.Completed("stop")))
        )
        val planner = NativeOpenAiPlanner(client, systemPrompt = "sys", prompt = "hi")

        val action = planner.nextAction(context, emptyList(), CoreTools.ALL)

        assertEquals(PlannedAction.Respond("Hello"), action)
        assertTrue(client.requests.single().tools.isNotEmpty())
    }
}
