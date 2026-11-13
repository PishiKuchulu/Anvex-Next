package com.anvex.core.agent

/**
 * The ONE planner interface of the new loop (ADR-0002). Three strategies
 * live behind this single contract, so the loop and the tools never change
 * per provider:
 *
 * 1. **Native tool-calling** — providers that support it, spoken in the
 *    OpenAI, Anthropic and Gemini wire formats directly.
 * 2. **Capability detection** — per provider/model, native tool-calling is
 *    used only when the endpoint is known to support it.
 * 3. **Bolt-text fallback** — where native tool-calling is unavailable, the
 *    planner falls back to the established bolt-text action format.
 *
 * Naming note (DECIDED at A1 sign-off): this is a new `:core:agent`
 * contract. The narrower `AgentPlanner` fun-interface in `:core:engine`
 * (`AgentOrchestration.kt`) stays untouched in A1/A2; whether and how
 * [ActionPlanner] subsumes it is an explicit A2 design decision.
 */
interface ActionPlanner {

    /**
     * Decides the next step of a turn: propose one tool call, or finish
     * with a final text response. [history] is the turn's event stream so
     * far (including tool results); [tools] is the callable tool set.
     */
    suspend fun nextAction(
        context: TurnContext,
        history: List<AgentEvent>,
        tools: List<ToolSpec>,
    ): PlannedAction
}

/** The outcome of one [ActionPlanner.nextAction] decision. */
sealed interface PlannedAction {

    /** Execute [call] next (through approval when its spec requires it). */
    data class CallTool(val call: ToolCall) : PlannedAction

    /** No more tools: [text] is the turn's final response. */
    data class Respond(val text: String) : PlannedAction
}
