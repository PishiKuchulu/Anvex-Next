package com.anvex.core.agent

import com.anvex.core.ai.AiError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for the A1 `:core:agent` interfaces: test-only fakes prove
 * the contracts compose into a full approval-gated turn. No production
 * implementation exists yet (A2+) — these fakes live in test sources only.
 */
class AgentContractsTest {

    /** Scripts: first a bash call, then a final response. */
    private class FakePlanner : ActionPlanner {
        var steps = 0
        override suspend fun nextAction(
            context: TurnContext,
            history: List<AgentEvent>,
            tools: List<ToolSpec>,
        ): PlannedAction {
            steps++
            return if (steps == 1) {
                PlannedAction.CallTool(
                    ToolCall(id = "call-1", toolName = CoreTools.BASH, argumentsJson = """{"command":"ls"}""")
                )
            } else {
                PlannedAction.Respond("done")
            }
        }
    }

    private class FakeGateway : ApprovalGateway {
        val requests = mutableListOf<ApprovalRequest>()
        override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
            requests += request
            return ApprovalDecision.Approved
        }
    }

    private class DenyingGateway : ApprovalGateway {
        override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision =
            ApprovalDecision.Denied("no")
    }

    private class FakeProgressStore : ProgressStore {
        val notes = mutableMapOf<String, String>()
        override suspend fun load(key: String): String? = notes[key]
        override suspend fun save(key: String, content: String) {
            notes[key] = content
        }
    }

    /** Minimal scripted engine: planner -> approval gate -> fake result. */
    private class FakeEngine(
        private val planner: ActionPlanner,
        private val gateway: ApprovalGateway,
    ) : AgentEngine {
        override fun runTurn(context: TurnContext, prompt: String): Flow<AgentEvent> = flow {
            val turnId = "turn-1"
            val history = mutableListOf<AgentEvent>()
            suspend fun publish(event: AgentEvent) {
                history += event
                emit(event)
            }
            publish(AgentEvent.TurnStarted(turnId))
            while (true) {
                when (val action = planner.nextAction(context, history, CoreTools.ALL)) {
                    is PlannedAction.Respond -> {
                        publish(AgentEvent.TextDelta(action.text))
                        publish(AgentEvent.TurnCompleted(turnId))
                        return@flow
                    }
                    is PlannedAction.CallTool -> {
                        val call = action.call
                        publish(AgentEvent.ToolCallProposed(call))
                        val spec = CoreTools.ALL.first { it.name == call.toolName }
                        if (spec.requiresApproval) {
                            when (gateway.requestApproval(ApprovalRequest(call, "bash is approval-gated"))) {
                                is ApprovalDecision.Approved -> publish(AgentEvent.ToolCallApproved(call.id))
                                is ApprovalDecision.Denied -> {
                                    publish(AgentEvent.ToolCallDenied(call.id))
                                    publish(AgentEvent.TurnFailed(turnId, AgentError.Policy("denied")))
                                    return@flow
                                }
                            }
                        }
                        publish(AgentEvent.ToolResultReceived(ToolResult(call.id, call.toolName, "file.txt")))
                    }
                }
            }
        }
    }

    private val context = TurnContext(sessionId = "s1", projectId = "p1", providerId = "prov", modelId = "m1")

    @Test fun coreToolSetIsExactlyReadWriteEditBash() {
        assertEquals(listOf("read", "write", "edit", "bash"), CoreTools.ALL.map { it.name })
        assertTrue(CoreTools.BASH_SPEC.requiresApproval)
        assertFalse(CoreTools.READ_SPEC.requiresApproval)
        assertFalse(CoreTools.WRITE_SPEC.requiresApproval)
        assertFalse(CoreTools.EDIT_SPEC.requiresApproval)
    }

    @Test fun approvalGatedBashTurnEmitsFullEventSequence() = runBlocking {
        val gateway = FakeGateway()
        val engine = FakeEngine(FakePlanner(), gateway)

        val events = engine.runTurn(context, "list files").toList()

        assertEquals(
            listOf(
                "TurnStarted", "ToolCallProposed", "ToolCallApproved",
                "ToolResultReceived", "TextDelta", "TurnCompleted",
            ),
            events.map { it::class.simpleName },
        )
        assertEquals(1, gateway.requests.size)
        assertEquals(CoreTools.BASH, gateway.requests.single().call.toolName)
    }

    @Test fun userDenialEndsTurnWithTypedPolicyFailure() = runBlocking {
        val engine = FakeEngine(FakePlanner(), DenyingGateway())

        val events = engine.runTurn(context, "list files").toList()

        assertEquals(
            listOf("TurnStarted", "ToolCallProposed", "ToolCallDenied", "TurnFailed"),
            events.map { it::class.simpleName },
        )
        val failed = events.last() as AgentEvent.TurnFailed
        assertEquals("turn-1", failed.turnId)
        assertEquals(AgentError.Policy("denied"), failed.error)
        assertEquals("denied", failed.error.message)
    }

    @Test fun agentErrorMessageDelegatesPerVariant() {
        // Addendum (a): Provider.message is the provider error's userTitle,
        // never the raw diagnostic message.
        val cause = AiError(AiError.Kind.NETWORK, "raw socket detail")
        assertEquals(cause.userTitle, AgentError.Provider(cause).message)
        assertFalse(AgentError.Provider(cause).message.contains("raw socket detail"))
        assertEquals("denied", AgentError.Policy("denied").message)
        assertEquals("read failed", AgentError.Tool("read", "read failed").message)
        assertEquals("loop bug", AgentError.Internal("loop bug").message)
    }

    @Test fun progressStoreRoundTripsNotes() = runBlocking {
        val store = FakeProgressStore()
        assertEquals(null, store.load("plan"))
        store.save("plan", "step 1 done")
        assertEquals("step 1 done", store.load("plan"))
    }
}
