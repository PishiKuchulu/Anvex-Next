package com.anvex.core.agent

import com.anvex.core.ai.AiError
import com.anvex.core.ai.ProviderCapabilities
import com.anvex.core.ai.ToolCallingOverride
import com.anvex.core.ai.ToolCallingSupport
import com.anvex.core.ai.isParameterRejection
import com.anvex.core.model.ProviderKind

/**
 * A2c (design note §5.3): session-scoped record of provider+model
 * pairs whose endpoints have PROVEN — by a tool-specific failure
 * this session — that they cannot take native tool calls. Marked
 * pairs are planned through the bolt strategy for the rest of the
 * session.
 *
 * "Session" is the lifetime of this state object. By default a
 * [PlannerRouter] owns a fresh one (session = router lifetime);
 * the wiring may share ONE instance across the per-turn routers of
 * a session so marks survive turn boundaries. In-memory only —
 * persistence is an A3 concern and deliberately absent here.
 */
class PlannerSessionState {

    private val boltOnlyKeys = mutableSetOf<String>()

    fun isBoltOnly(providerId: String, modelId: String): Boolean =
        key(providerId, modelId) in boltOnlyKeys

    fun markBoltOnly(providerId: String, modelId: String) {
        boltOnlyKeys += key(providerId, modelId)
    }

    private fun key(providerId: String, modelId: String): String = "$providerId|$modelId"
}

/**
 * The capability router (A2 design note §5.4): an [ActionPlanner]
 * that selects — per turn — between the native tool-calling planner
 * and the bolt-text planner, from a capability snapshot PASSED IN at
 * construction ([kind] + [toolCallingOverride], resolved by the
 * `:core:android` wiring). Capability *knowledge* stays in
 * `:core:ai` ([ProviderCapabilities]); this class only selects.
 * Neither layer reads settings.
 *
 * Selection order (§5.4, with the session mark first):
 *
 * 0. A provider+model marked bolt-only in [sessionState] → bolt.
 *    The mark is empirical evidence from THIS session and outranks
 *    the static snapshot; a new session starts clean.
 * 1. `FORCE_BOLT` → bolt.
 * 2. `FORCE_NATIVE` → native (downgrade armed).
 * 3. `AUTO` → table: `NATIVE` → native (not armed);
 *    `WIRE_CAPABLE_UNKNOWN` → native (downgrade armed);
 *    `NONE` → bolt.
 *
 * **Bounded runtime downgrade** (§5.3): while armed, a tool-specific
 * failure from the native planner — an HTTP 400 parameter rejection
 * (the shared [AiError.isParameterRejection] heuristic, the same one
 * the OpenAI client retries on) or a `PROTOCOL` failure, in both
 * cases only when tools were actually attached to the failed
 * request — marks the provider+model bolt-only in [sessionState]
 * and re-plans the CURRENT step ONCE through the bolt planner with
 * the same history. The bolt call is not itself guarded: its failure
 * propagates. One downgrade per provider+model per session, no
 * retry loop, no probing requests.
 *
 * **Per-turn planners.** Strategies are per-turn instances (their
 * prompt is baked in at construction — the [ActionPlanner] contract
 * carries no prompt), so the router takes FACTORIES and builds the
 * selected strategy lazily on a turn's first [nextAction], reusing
 * it for the turn's remaining steps. Turn boundaries are detected
 * from the `TurnStarted` id in the history — the loop's history
 * always carries it (direct calls without one are treated as one
 * continuing turn).
 */
class PlannerRouter(
    private val kind: ProviderKind,
    private val toolCallingOverride: ToolCallingOverride = ToolCallingOverride.AUTO,
    private val sessionState: PlannerSessionState = PlannerSessionState(),
    private val nativeFactory: () -> ActionPlanner,
    private val boltFactory: () -> ActionPlanner,
) : ActionPlanner {

    private var activeTurnId: String? = null
    private var activePlanner: ActionPlanner? = null
    private var activeIsNative: Boolean = false
    private var downgradeArmed: Boolean = false

    override suspend fun nextAction(
        context: TurnContext,
        history: List<AgentEvent>,
        tools: List<ToolSpec>,
    ): PlannedAction {
        val planner = plannerFor(context, history)
        if (!activeIsNative) return planner.nextAction(context, history, tools)
        return try {
            planner.nextAction(context, history, tools)
        } catch (e: ProviderCallException) {
            if (!downgradeArmed || !e.error.isToolSpecificFailure(toolsAttached = tools.isNotEmpty())) {
                throw e
            }
            sessionState.markBoltOnly(context.providerId, context.modelId)
            val bolt = activate(boltFactory(), isNative = false)
            bolt.nextAction(context, history, tools)
        }
    }

    /** The selected strategy for the current turn, built on first use per turn. */
    private fun plannerFor(context: TurnContext, history: List<AgentEvent>): ActionPlanner {
        val turnId = history.filterIsInstance<AgentEvent.TurnStarted>().lastOrNull()?.turnId
        val current = activePlanner
        if (current != null && turnId == activeTurnId) return current

        val useNative: Boolean
        val armed: Boolean
        when {
            sessionState.isBoltOnly(context.providerId, context.modelId) -> {
                useNative = false
                armed = false
            }
            toolCallingOverride == ToolCallingOverride.FORCE_BOLT -> {
                useNative = false
                armed = false
            }
            toolCallingOverride == ToolCallingOverride.FORCE_NATIVE -> {
                useNative = true
                armed = true
            }
            else -> when (ProviderCapabilities.toolCalling(kind)) {
                ToolCallingSupport.NATIVE -> {
                    useNative = true
                    armed = false
                }
                ToolCallingSupport.WIRE_CAPABLE_UNKNOWN -> {
                    useNative = true
                    armed = true
                }
                ToolCallingSupport.NONE -> {
                    useNative = false
                    armed = false
                }
            }
        }
        downgradeArmed = armed
        activeTurnId = turnId
        return activate(if (useNative) nativeFactory() else boltFactory(), isNative = useNative)
    }

    private fun activate(planner: ActionPlanner, isNative: Boolean): ActionPlanner {
        activePlanner = planner
        activeIsNative = isNative
        return planner
    }
}

/**
 * §5.3's tool-specific failure: an HTTP 400 parameter rejection or a
 * `PROTOCOL` failure — and only while tools were actually attached
 * to the failed request (a no-tools PROTOCOL failure, e.g. an empty
 * stream, says nothing about tool support).
 */
private fun AiError.isToolSpecificFailure(toolsAttached: Boolean): Boolean =
    toolsAttached &&
        (kind == AiError.Kind.PROTOCOL || (httpStatus == 400 && isParameterRejection()))
