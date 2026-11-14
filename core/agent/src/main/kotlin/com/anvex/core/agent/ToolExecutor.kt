package com.anvex.core.agent

/**
 * The execution boundary of the new loop (A2 design note §3): A1 gave
 * the loop tool *descriptions* ([ToolSpec]/[ToolCall]/[ToolResult]) but
 * no port that *runs* a call. The gate (assessment + approval, ADR-0003)
 * lives in the loop (`TurnRunner`); the side effects live behind this
 * port, implemented in `:core:android`.
 *
 * The [context] parameter is required by the contracts themselves: the
 * workspace is identified by [TurnContext.projectId], the same reason
 * [ActionPlanner.nextAction] receives the context separately.
 *
 * Failure contract: an *expected* tool failure (bad path, refused file,
 * non-zero exit, unavailable workspace) is returned as
 * [ToolResult] with `isError = true` — this function never throws for
 * one. Unexpected exceptions are the loop's [AgentError.Internal]
 * concern and may propagate.
 *
 * Precedent: `:core:engine`'s `AgentActionExecutor`
 * (`AgentOrchestration.kt`) is this exact boundary shape for the batch
 * task path; the two coexist because their paths do.
 */
interface ToolExecutor {
    suspend fun execute(context: TurnContext, call: ToolCall): ToolResult
}
