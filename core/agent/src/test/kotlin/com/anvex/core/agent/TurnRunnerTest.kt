package com.anvex.core.agent

import com.anvex.core.ai.AiError
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2a (§7): the TurnRunner gate — planner → ToolCallProposed →
 * CoreToolActions/ActionSafetyPolicy assessment → ApprovalGateway →
 * ToolExecutor → events, with every failure path mapped to AgentError.
 */
class TurnRunnerTest {

    private val context = TurnContext(sessionId = "s1", projectId = "p1", providerId = "prov", modelId = "m1")

    private class ScriptedPlanner(private val script: List<PlannedAction>) : ActionPlanner {
        val histories = mutableListOf<List<AgentEvent>>()
        private var index = 0
        override suspend fun nextAction(
            context: TurnContext,
            history: List<AgentEvent>,
            tools: List<ToolSpec>,
        ): PlannedAction {
            histories += history
            return script[index++]
        }
    }

    private class ThrowingPlanner(private val failure: Exception) : ActionPlanner {
        override suspend fun nextAction(
            context: TurnContext,
            history: List<AgentEvent>,
            tools: List<ToolSpec>,
        ): PlannedAction = throw failure
    }

    private class FakeExecutor(
        private val behavior: (ToolCall) -> ToolResult = { ToolResult(it.id, it.toolName, "ok") },
    ) : ToolExecutor {
        val calls = mutableListOf<ToolCall>()
        override suspend fun execute(context: TurnContext, call: ToolCall): ToolResult {
            calls += call
            return behavior(call)
        }
    }

    private class FakeGateway(private val decision: ApprovalDecision) : ApprovalGateway {
        val requests = mutableListOf<ApprovalRequest>()
        override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
            requests += request
            return decision
        }
    }

    private fun bashCall() = ToolCall("c1", CoreTools.BASH, """{"command":"ls"}""")

    @Test fun bashTurnEmitsFullEventSequence() = runBlocking {
        val planner = ScriptedPlanner(listOf(PlannedAction.CallTool(bashCall()), PlannedAction.Respond("done")))
        val executor = FakeExecutor()
        val gateway = FakeGateway(ApprovalDecision.Approved)
        val runner = TurnRunner(planner, executor, gateway)

        val events = runner.runTurn(context, "list files").toList()

        assertEquals(
            listOf(
                "TurnStarted", "ToolCallProposed", "ToolCallApproved",
                "ToolResultReceived", "TextDelta", "TurnCompleted",
            ),
            events.map { it::class.simpleName },
        )
        assertEquals(1, gateway.requests.size)
        assertEquals(listOf(bashCall()), executor.calls)
        // The planner's second decision saw the tool result in history.
        val secondHistory = planner.histories[1]
        assertTrue(secondHistory.any { it is AgentEvent.ToolResultReceived })
    }

    @Test fun userDenialEndsTurnWithPolicyFailureAndNeverExecutes() = runBlocking {
        val planner = ScriptedPlanner(listOf(PlannedAction.CallTool(bashCall())))
        val executor = FakeExecutor()
        val gateway = FakeGateway(ApprovalDecision.Denied("not now"))
        val runner = TurnRunner(planner, executor, gateway)

        val events = runner.runTurn(context, "list files").toList()

        assertEquals(
            listOf("TurnStarted", "ToolCallProposed", "ToolCallDenied", "TurnFailed"),
            events.map { it::class.simpleName },
        )
        val failed = events.last() as AgentEvent.TurnFailed
        assertTrue(failed.error is AgentError.Policy)
        assertTrue(executor.calls.isEmpty())
    }

    @Test fun policyDenialForUnofferedToolEndsTurnWithoutGatewayOrExecutor() = runBlocking {
        val planner = ScriptedPlanner(listOf(PlannedAction.CallTool(ToolCall("c9", "mcp", "{}"))))
        val executor = FakeExecutor()
        val gateway = FakeGateway(ApprovalDecision.Approved)
        val runner = TurnRunner(planner, executor, gateway)

        val events = runner.runTurn(context, "use mcp").toList()

        assertEquals(
            listOf("TurnStarted", "ToolCallProposed", "TurnFailed"),
            events.map { it::class.simpleName },
        )
        val failed = events.last() as AgentEvent.TurnFailed
        assertTrue(failed.error is AgentError.Policy)
        assertTrue(gateway.requests.isEmpty())
        assertTrue(executor.calls.isEmpty())
    }

    @Test fun providerFailureEndsTurnWithProviderError() = runBlocking {
        val cause = AiError(AiError.Kind.NETWORK, "socket closed")
        val runner = TurnRunner(ThrowingPlanner(ProviderCallException(cause)), FakeExecutor(), FakeGateway(ApprovalDecision.Approved))

        val events = runner.runTurn(context, "hi").toList()

        val failed = events.last() as AgentEvent.TurnFailed
        assertEquals(AgentError.Provider(cause), failed.error)
        assertEquals(cause.userTitle, failed.error.message)
    }

    @Test fun unexpectedPlannerFailureEndsTurnWithInternalError() = runBlocking {
        val runner = TurnRunner(
            ThrowingPlanner(IllegalStateException("planner bug")),
            FakeExecutor(),
            FakeGateway(ApprovalDecision.Approved),
        )

        val events = runner.runTurn(context, "hi").toList()

        val failed = events.last() as AgentEvent.TurnFailed
        assertTrue(failed.error is AgentError.Internal)
    }

    @Test fun toolFailureIsFedBackAsErrorResultNotADeadTurn() = runBlocking {
        val planner = ScriptedPlanner(listOf(PlannedAction.CallTool(bashCall()), PlannedAction.Respond("recovered")))
        val executor = FakeExecutor { ToolResult(it.id, it.toolName, "exit 1", isError = true) }
        val runner = TurnRunner(planner, executor, FakeGateway(ApprovalDecision.Approved))

        val events = runner.runTurn(context, "run it").toList()

        val resultEvent = events.filterIsInstance<AgentEvent.ToolResultReceived>().single()
        assertTrue(resultEvent.result.isError)
        assertTrue(events.last() is AgentEvent.TurnCompleted)
        // The planner saw the error result in its next history.
        assertTrue(
            planner.histories[1].filterIsInstance<AgentEvent.ToolResultReceived>().single().result.isError
        )
    }

    @Test fun bashAlwaysRequestsGatewayApprovalBeforeExecution() = runBlocking {
        // INVARIANT LOCK (A2c carry-forward): `AgentLoopToolExecutor`
        // (core:android) answers the shell runtime's own approval seam
        // with an already-decided `{ true }` lambda. That is safe ONLY
        // because THIS gate always obtains gateway approval for a bash
        // call before executing it — which holds only because
        // CoreTools.BASH_SPEC.requiresApproval is true. This test is
        // the tripwire: a benign `ls` (whose safety assessment does
        // not escalate) must STILL be gateway-approved, and approval
        // must precede execution. If the spec flag ever flips, the
        // gateway request disappears here and this test fails —
        // before the executor's `{ true }` can become an unprompted
        // execution path.
        assertTrue(CoreTools.BASH_SPEC.requiresApproval)
        val order = mutableListOf<String>()
        val gateway = object : ApprovalGateway {
            override suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
                order += "approval:${request.call.toolName}"
                return ApprovalDecision.Approved
            }
        }
        val executor = object : ToolExecutor {
            override suspend fun execute(context: TurnContext, call: ToolCall): ToolResult {
                order += "execute:${call.toolName}"
                return ToolResult(call.id, call.toolName, "ok")
            }
        }
        val planner = ScriptedPlanner(listOf(PlannedAction.CallTool(bashCall()), PlannedAction.Respond("done")))
        val runner = TurnRunner(planner, executor, gateway)

        val events = runner.runTurn(context, "list files").toList()

        assertEquals(listOf("approval:bash", "execute:bash"), order)
        assertTrue(events.last() is AgentEvent.TurnCompleted)
    }

    @Test fun safeReadSkipsApprovalGateway() = runBlocking {
        val readCall = ToolCall("c2", CoreTools.READ, """{"path":"a.txt"}""")
        val planner = ScriptedPlanner(listOf(PlannedAction.CallTool(readCall), PlannedAction.Respond("read it")))
        val gateway = FakeGateway(ApprovalDecision.Denied("should not be asked"))
        val runner = TurnRunner(planner, FakeExecutor(), gateway)

        val events = runner.runTurn(context, "read a.txt").toList()

        assertTrue(gateway.requests.isEmpty())
        assertFalse(events.any { it is AgentEvent.ToolCallApproved })
        assertTrue(events.last() is AgentEvent.TurnCompleted)
    }
}
