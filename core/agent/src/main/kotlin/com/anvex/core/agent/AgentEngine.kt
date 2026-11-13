package com.anvex.core.agent

import kotlinx.coroutines.flow.Flow

/**
 * The single orchestrator facade for every chat/agent turn (ADR-0002,
 * `ARCHITECTURE.md` — "ONE orchestrator facade"). Screens and ViewModels
 * never talk to engines (native loop, Pi, planners) directly; they collect
 * the [AgentEvent] stream of one turn through this interface.
 *
 * A1 ships this contract only — no implementation lives in `:core:agent`
 * yet. The native loop arrives in A2+; until parity the legacy path keeps
 * serving production behind the `agentEngine = legacy | native` flag.
 *
 * Mapping onto the existing UI state: `:core:android`'s `ChatSession.UiState`
 * remains the ONE chat UI state and is **not** replaced by these contracts.
 * A bridge in `:core:android` reduces this event stream into `UiState`:
 * [AgentEvent.TurnStarted] → busy/status line; [AgentEvent.TextDelta] and
 * [AgentEvent.ReasoningDelta] → streaming text/reasoning appends;
 * [AgentEvent.ToolCallProposed] → pending action/approval display;
 * [AgentEvent.ToolCallApproved]/[AgentEvent.ToolCallDenied] → approval
 * outcome; [AgentEvent.ToolResultReceived] → action result, artifact and
 * file-revision updates; [AgentEvent.TurnCompleted] → idle;
 * [AgentEvent.TurnFailed] → the typed error surface.
 */
interface AgentEngine {

    /**
     * Runs one turn for [prompt] in [context] and returns its event stream.
     * The flow is cold: collection starts the turn, cancellation abandons
     * it. It always ends with exactly one terminal event —
     * [AgentEvent.TurnCompleted] or [AgentEvent.TurnFailed].
     */
    fun runTurn(context: TurnContext, prompt: String): Flow<AgentEvent>
}
