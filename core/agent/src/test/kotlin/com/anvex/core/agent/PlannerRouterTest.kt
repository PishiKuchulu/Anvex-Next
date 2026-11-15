package com.anvex.core.agent

import com.anvex.core.ai.AiError
import com.anvex.core.ai.ToolCallingOverride
import com.anvex.core.model.ProviderKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A2c (design note §5.3/§5.4): the capability router — selection by
 * override × static table, session bolt-only marks, and the BOUNDED
 * runtime downgrade (one re-plan, no loop, no probing).
 */
class PlannerRouterTest {

    private val context = TurnContext(sessionId = "s1", projectId = "p1", providerId = "prov", modelId = "m1")

    /** A scripted strategy planner; [behavior] receives the 0-based call index. */
    private class FakePlanner(private val behavior: (Int) -> PlannedAction) : ActionPlanner {
        val histories = mutableListOf<List<AgentEvent>>()
        override suspend fun nextAction(
            context: TurnContext,
            history: List<AgentEvent>,
            tools: List<ToolSpec>,
        ): PlannedAction {
            histories += history
            return behavior(histories.size - 1)
        }
    }

    /** Records every planner instance a factory creates (factories build per-turn planners). */
    private class Factory(private val make: () -> FakePlanner) {
        val created = mutableListOf<FakePlanner>()
        fun create(): FakePlanner = make().also { created += it }
        val totalCalls: Int get() = created.sumOf { it.histories.size }
    }

    private fun respondingFactory(label: String) =
        Factory { FakePlanner { PlannedAction.Respond(label) } }

    private fun throwingFactory(error: Exception) =
        Factory { FakePlanner { throw error } }

    private fun router(
        kind: ProviderKind,
        override: ToolCallingOverride = ToolCallingOverride.AUTO,
        state: PlannerSessionState = PlannerSessionState(),
        native: Factory = respondingFactory("native"),
        bolt: Factory = respondingFactory("bolt"),
    ) = PlannerRouter(
        kind = kind,
        toolCallingOverride = override,
        sessionState = state,
        nativeFactory = native::create,
        boltFactory = bolt::create,
    )

    private fun turnHistory(turnId: String) = listOf(AgentEvent.TurnStarted(turnId))

    // ------------------------------------------------------------ selection

    @Test fun selectionMatrix() = runBlocking {
        // kind, override -> expected serving strategy label
        val cases = listOf(
            Triple(ProviderKind.OPENAI, ToolCallingOverride.FORCE_BOLT, "bolt"),
            Triple(ProviderKind.GEMINI, ToolCallingOverride.FORCE_BOLT, "bolt"),
            Triple(ProviderKind.OPENAI, ToolCallingOverride.FORCE_NATIVE, "native"),
            Triple(ProviderKind.CLINE, ToolCallingOverride.FORCE_NATIVE, "native"),
            Triple(ProviderKind.OPENAI, ToolCallingOverride.AUTO, "native"),
            Triple(ProviderKind.CUSTOM_ANTHROPIC, ToolCallingOverride.AUTO, "native"),
            Triple(ProviderKind.GEMINI, ToolCallingOverride.AUTO, "native"),
            Triple(ProviderKind.OPENROUTER, ToolCallingOverride.AUTO, "native"),
            Triple(ProviderKind.ZAI_ACCOUNT, ToolCallingOverride.AUTO, "native"),
            Triple(ProviderKind.CLINE, ToolCallingOverride.AUTO, "bolt"),
            Triple(ProviderKind.ANTIGRAVITY, ToolCallingOverride.AUTO, "bolt"),
        )
        for ((kind, override, expected) in cases) {
            val native = respondingFactory("native")
            val bolt = respondingFactory("bolt")
            val action = router(kind, override, native = native, bolt = bolt)
                .nextAction(context, turnHistory("t"), CoreTools.ALL)
            assertEquals("$kind/$override", PlannedAction.Respond(expected), action)
            assertEquals("$kind/$override native calls", if (expected == "native") 1 else 0, native.totalCalls)
            assertEquals("$kind/$override bolt calls", if (expected == "bolt") 1 else 0, bolt.totalCalls)
        }
    }

    // ------------------------------------------------------------ downgrade

    private fun parameterRejection400() = ProviderCallException(
        AiError(AiError.Kind.UNKNOWN, "tools: unsupported parameter", httpStatus = 400)
    )

    @Test fun toolSpecific400DowngradesAndReplansOnceThenSticksForTheSession() = runBlocking {
        val state = PlannerSessionState()
        val native = throwingFactory(parameterRejection400())
        val bolt = respondingFactory("bolt")
        val first = router(ProviderKind.OPENROUTER, state = state, native = native, bolt = bolt)

        val action = first.nextAction(context, turnHistory("t1"), CoreTools.ALL)

        assertEquals(PlannedAction.Respond("bolt"), action)
        assertEquals("native attempted once", 1, native.totalCalls)
        assertEquals("bolt re-planned once", 1, bolt.totalCalls)
        assertTrue(state.isBoltOnly("prov", "m1"))

        // Second turn (new router + fresh planners, SAME session
        // state): straight to bolt, native never attempted.
        val native2 = respondingFactory("native")
        val bolt2 = respondingFactory("bolt")
        val second = router(ProviderKind.OPENROUTER, state = state, native = native2, bolt = bolt2)
        val action2 = second.nextAction(context, turnHistory("t2"), CoreTools.ALL)
        assertEquals(PlannedAction.Respond("bolt"), action2)
        assertEquals(0, native2.totalCalls)
        assertEquals(1, bolt2.totalCalls)
    }

    @Test fun protocolFailureWhileToolsAttachedDowngrades() = runBlocking {
        val native = throwingFactory(
            ProviderCallException(AiError(AiError.Kind.PROTOCOL, "malformed tool arguments"))
        )
        val bolt = respondingFactory("bolt")
        val action = router(ProviderKind.DEEPSEEK, native = native, bolt = bolt)
            .nextAction(context, turnHistory("t"), CoreTools.ALL)
        assertEquals(PlannedAction.Respond("bolt"), action)
        assertEquals(1, native.totalCalls)
        assertEquals(1, bolt.totalCalls)
    }

    @Test fun protocolFailureWithoutToolsAttachedDoesNotDowngrade() = runBlocking {
        val cause = ProviderCallException(AiError(AiError.Kind.PROTOCOL, "stream ended without content"))
        val native = throwingFactory(cause)
        val bolt = respondingFactory("bolt")
        try {
            router(ProviderKind.OPENROUTER, native = native, bolt = bolt)
                .nextAction(context, turnHistory("t"), emptyList())
            fail("expected ProviderCallException")
        } catch (e: ProviderCallException) {
            assertEquals(cause.error, e.error)
        }
        assertEquals(0, bolt.totalCalls)
    }

    @Test fun nonToolSpecificFailureDoesNotDowngrade() = runBlocking {
        val state = PlannerSessionState()
        val cause = ProviderCallException(AiError(AiError.Kind.NETWORK, "socket closed"))
        val native = throwingFactory(cause)
        val bolt = respondingFactory("bolt")
        try {
            router(ProviderKind.OPENROUTER, state = state, native = native, bolt = bolt)
                .nextAction(context, turnHistory("t1"), CoreTools.ALL)
            fail("expected ProviderCallException")
        } catch (e: ProviderCallException) {
            assertEquals(cause.error, e.error)
        }
        assertEquals(0, bolt.totalCalls)
        assertTrue(!state.isBoltOnly("prov", "m1"))
    }

    @Test fun badModel400IsNotAParameterRejectionAndDoesNotDowngrade() = runBlocking {
        val cause = ProviderCallException(
            AiError(AiError.Kind.UNKNOWN, "The model 'x' does not exist", httpStatus = 400)
        )
        val native = throwingFactory(cause)
        val bolt = respondingFactory("bolt")
        try {
            router(ProviderKind.GROQ, native = native, bolt = bolt)
                .nextAction(context, turnHistory("t"), CoreTools.ALL)
            fail("expected ProviderCallException")
        } catch (e: ProviderCallException) {
            assertEquals(cause.error, e.error)
        }
        assertEquals(0, bolt.totalCalls)
    }

    @Test fun nativeTableSupportIsNotArmedSoProtocolFailurePropagates() = runBlocking {
        // §5.4 arms the downgrade for FORCE_NATIVE and for AUTO +
        // WIRE_CAPABLE_UNKNOWN only; AUTO + NATIVE fails the turn.
        val cause = ProviderCallException(AiError(AiError.Kind.PROTOCOL, "malformed tool arguments"))
        val native = throwingFactory(cause)
        val bolt = respondingFactory("bolt")
        try {
            router(ProviderKind.OPENAI, native = native, bolt = bolt)
                .nextAction(context, turnHistory("t"), CoreTools.ALL)
            fail("expected ProviderCallException")
        } catch (e: ProviderCallException) {
            assertEquals(cause.error, e.error)
        }
        assertEquals(0, bolt.totalCalls)
    }

    @Test fun forceNativeArmsTheDowngradeEvenForNoneSupport() = runBlocking {
        val native = throwingFactory(parameterRejection400())
        val bolt = respondingFactory("bolt")
        val action = router(
            ProviderKind.CLINE,
            override = ToolCallingOverride.FORCE_NATIVE,
            native = native,
            bolt = bolt,
        ).nextAction(context, turnHistory("t"), CoreTools.ALL)
        assertEquals(PlannedAction.Respond("bolt"), action)
        assertEquals(1, native.totalCalls)
        assertEquals(1, bolt.totalCalls)
    }

    @Test fun boltFailureAfterDowngradePropagatesWithoutASecondReplan() = runBlocking {
        val boltCause = ProviderCallException(AiError(AiError.Kind.NETWORK, "socket closed"))
        val native = throwingFactory(
            ProviderCallException(AiError(AiError.Kind.PROTOCOL, "malformed tool arguments"))
        )
        val bolt = throwingFactory(boltCause)
        try {
            router(ProviderKind.OPENROUTER, native = native, bolt = bolt)
                .nextAction(context, turnHistory("t"), CoreTools.ALL)
            fail("expected ProviderCallException")
        } catch (e: ProviderCallException) {
            assertEquals(boltCause.error, e.error)
        }
        assertEquals("no retry loop: bolt attempted exactly once", 1, bolt.totalCalls)
        assertEquals(1, native.totalCalls)
    }

    @Test fun unexpectedPlannerFailureDoesNotDowngrade() = runBlocking {
        val native = throwingFactory(IllegalStateException("planner bug"))
        val bolt = respondingFactory("bolt")
        try {
            router(ProviderKind.OPENROUTER, native = native, bolt = bolt)
                .nextAction(context, turnHistory("t"), CoreTools.ALL)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("planner bug", e.message)
        }
        assertEquals(0, bolt.totalCalls)
    }

    @Test fun downgradeMarkIsScopedToProviderAndModel() = runBlocking {
        val state = PlannerSessionState()
        val native = throwingFactory(parameterRejection400())
        val bolt = respondingFactory("bolt")
        router(ProviderKind.OPENROUTER, state = state, native = native, bolt = bolt)
            .nextAction(context, turnHistory("t1"), CoreTools.ALL)
        assertTrue(state.isBoltOnly("prov", "m1"))

        // A different provider on the same session state is unaffected.
        val otherContext = context.copy(providerId = "other", modelId = "m1")
        val native2 = respondingFactory("native")
        val bolt2 = respondingFactory("bolt")
        val action = router(ProviderKind.OPENROUTER, state = state, native = native2, bolt = bolt2)
            .nextAction(otherContext, turnHistory("t2"), CoreTools.ALL)
        assertEquals(PlannedAction.Respond("native"), action)
        assertEquals(1, native2.totalCalls)
        assertEquals(0, bolt2.totalCalls)
    }

    @Test fun midTurnDowngradeReplansWithTheSameHistory() = runBlocking {
        // Step 1 succeeds natively; step 2 fails tool-specifically.
        // The re-plan must hand bolt the SAME (grown) history, and
        // later steps of the turn keep using the bolt planner.
        val call = ToolCall("c1", CoreTools.READ, """{"path":"a.txt"}""")
        val native = Factory {
            FakePlanner { index ->
                if (index == 0) PlannedAction.CallTool(call)
                else throw ProviderCallException(AiError(AiError.Kind.PROTOCOL, "malformed tool arguments"))
            }
        }
        val bolt = respondingFactory("bolt")
        val r = router(ProviderKind.MISTRAL, native = native, bolt = bolt)

        val step1 = r.nextAction(context, turnHistory("t1"), CoreTools.ALL)
        assertEquals(PlannedAction.CallTool(call), step1)

        val grownHistory = turnHistory("t1") + listOf(
            AgentEvent.ToolCallProposed(call),
            AgentEvent.ToolResultReceived(ToolResult("c1", CoreTools.READ, "file body")),
        )
        val step2 = r.nextAction(context, grownHistory, CoreTools.ALL)
        assertEquals(PlannedAction.Respond("bolt"), step2)
        assertEquals("bolt re-plan saw the same history", grownHistory, bolt.created.single().histories.single())

        val step3 = r.nextAction(context, grownHistory, CoreTools.ALL)
        assertEquals(PlannedAction.Respond("bolt"), step3)
        assertEquals("native never retried within the turn", 2, native.totalCalls)
        assertEquals(2, bolt.totalCalls)
    }
}
