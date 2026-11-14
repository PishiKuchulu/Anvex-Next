package com.anvex.core.agent

import com.anvex.core.engine.ActionSafetyPolicy
import com.anvex.core.engine.BoltAction
import com.anvex.core.engine.CoreToolActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * The minimal [AgentEngine] of the new native loop (A2 design note
 * §3/§6): planner → gate → executor → events. Constructor-injected
 * [planner]/[executor]/[gateway] — no router yet (§5.4: the router
 * arrives with the second strategy in A2c).
 *
 * The gate lives HERE, never inside the executor (ADR-0003 — one
 * safety policy, no parallel policy):
 *
 * 1. The planner proposes a [ToolCall] → [AgentEvent.ToolCallProposed].
 * 2. The call is mapped through the ONE shared primitive
 *    ([CoreToolActions.toBoltAction]; for `edit`, `content` is the FULL
 *    `argumentsJson` mutation fingerprint — sign-off addendum (b), no
 *    truncation) and assessed by [ActionSafetyPolicy.assess].
 * 3. Approval is required when the assessment requires it OR the tool
 *    spec does ([ToolSpec.requiresApproval], e.g. `bash`). The
 *    [ApprovalGateway] is the only user-facing prompt; approval emits
 *    [AgentEvent.ToolCallApproved], denial emits
 *    [AgentEvent.ToolCallDenied] and ends the turn as
 *    `TurnFailed(turnId, AgentError.Policy)` (A1 semantics, Q4: a
 *    denial is a decision, not an invitation to negotiate).
 * 4. Only then does [ToolExecutor.execute] run; its result — success
 *    or `isError` — is emitted as [AgentEvent.ToolResultReceived] and
 *    fed back to the planner. A tool failure is NOT a dead turn.
 *
 * Policy denial: a call naming a tool outside the offered set is
 * denied by policy outright (the loop cannot execute what it did not
 * offer) — no gateway question, no execution, `TurnFailed(Policy)`.
 *
 * Every failure path maps to [AgentError]: provider failures →
 * [AgentError.Provider] (via [ProviderCallException]), tool execution
 * exceptions → [AgentError.Tool], denials → [AgentError.Policy],
 * anything else → [AgentError.Internal].
 */
class TurnRunner(
    private val planner: ActionPlanner,
    private val executor: ToolExecutor,
    private val gateway: ApprovalGateway,
    private val tools: List<ToolSpec> = CoreTools.ALL,
) : AgentEngine {

    override fun runTurn(context: TurnContext, prompt: String): Flow<AgentEvent> = flow {
        val turnId = UUID.randomUUID().toString()
        val history = mutableListOf<AgentEvent>()
        suspend fun publish(event: AgentEvent) {
            history += event
            emit(event)
        }

        publish(AgentEvent.TurnStarted(turnId))
        var steps = 0
        while (true) {
            if (++steps > MAX_STEPS) {
                publish(
                    AgentEvent.TurnFailed(
                        turnId,
                        AgentError.Internal("Turn exceeded the $MAX_STEPS-step limit"),
                    )
                )
                return@flow
            }
            val action = try {
                planner.nextAction(context, history.toList(), tools)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderCallException) {
                publish(AgentEvent.TurnFailed(turnId, AgentError.Provider(e.error)))
                return@flow
            } catch (e: Exception) {
                publish(
                    AgentEvent.TurnFailed(
                        turnId,
                        AgentError.Internal(e.message ?: "Planner failed"),
                    )
                )
                return@flow
            }
            when (action) {
                is PlannedAction.Respond -> {
                    publish(AgentEvent.TextDelta(action.text))
                    publish(AgentEvent.TurnCompleted(turnId))
                    return@flow
                }
                is PlannedAction.CallTool -> {
                    val call = action.call
                    publish(AgentEvent.ToolCallProposed(call))
                    val spec = tools.firstOrNull { it.name == call.toolName }
                    if (spec == null) {
                        publish(
                            AgentEvent.TurnFailed(
                                turnId,
                                AgentError.Policy(
                                    "Tool '${call.toolName}' is not part of the offered tool set"
                                ),
                            )
                        )
                        return@flow
                    }
                    val assessment = ActionSafetyPolicy.assess(gateAction(call))
                    if (assessment.requiresApproval || spec.requiresApproval) {
                        val reason = if (assessment.requiresApproval) {
                            assessment.reason
                        } else {
                            "Tool '${call.toolName}' requires approval before execution"
                        }
                        val decision = try {
                            gateway.requestApproval(ApprovalRequest(call, reason))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            publish(
                                AgentEvent.TurnFailed(
                                    turnId,
                                    AgentError.Internal(e.message ?: "Approval gateway failed"),
                                )
                            )
                            return@flow
                        }
                        when (decision) {
                            is ApprovalDecision.Approved ->
                                publish(AgentEvent.ToolCallApproved(call.id))
                            is ApprovalDecision.Denied -> {
                                publish(AgentEvent.ToolCallDenied(call.id, decision.reason))
                                publish(
                                    AgentEvent.TurnFailed(
                                        turnId,
                                        AgentError.Policy(
                                            decision.reason
                                                ?: "The user denied the '${call.toolName}' call"
                                        ),
                                    )
                                )
                                return@flow
                            }
                        }
                    }
                    val result = try {
                        executor.execute(context, call)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        publish(
                            AgentEvent.TurnFailed(
                                turnId,
                                AgentError.Tool(
                                    call.toolName,
                                    e.message ?: "Tool execution failed",
                                ),
                            )
                        )
                        return@flow
                    }
                    publish(AgentEvent.ToolResultReceived(result))
                }
            }
        }
    }

    /**
     * Maps a call to the [BoltAction] the policy judges, through the
     * shared primitive. Field extraction is best-effort (malformed
     * arguments still assess — the executor rejects them strictly
     * before any side effect), EXCEPT `edit`, whose fingerprint is the
     * full `argumentsJson` by contract (addendum (b)).
     */
    private fun gateAction(call: ToolCall): BoltAction {
        val args = parseArguments(call.argumentsJson)
        val path = args?.get("path")?.jsonPrimitive?.contentOrNull
        val command = args?.get("command")?.jsonPrimitive?.contentOrNull
        val content = when (call.toolName) {
            CoreTools.EDIT -> call.argumentsJson
            CoreTools.WRITE -> args?.get("content")?.jsonPrimitive?.contentOrNull
            else -> null
        }
        return CoreToolActions.toBoltAction(
            toolName = call.toolName,
            path = path,
            content = content,
            command = command,
        )
    }

    private fun parseArguments(argumentsJson: String): JsonObject? =
        try {
            Json.parseToJsonElement(argumentsJson) as? JsonObject
        } catch (_: Exception) {
            null
        }

    private companion object {
        /** Runaway-loop guard: a turn never takes more planner steps than this. */
        const val MAX_STEPS = 100
    }
}
