package com.anvex.core.agent

import com.anvex.core.ai.AiError
import com.anvex.core.ai.AiToolCall
import com.anvex.core.ai.AiToolDefinition
import com.anvex.core.ai.ChatRequest
import com.anvex.core.ai.ChatTurn
import com.anvex.core.ai.ProviderClient
import com.anvex.core.ai.StreamEvent
import com.anvex.core.model.MessageRole

/**
 * Thrown by a planner when the provider call itself fails. [TurnRunner]
 * maps it to [AgentError.Provider]; the [error] is `:core:ai`'s typed
 * error, so the bridge can hand the full [AiError] to the UI.
 */
class ProviderCallException(val error: AiError) : Exception(error.message)

/**
 * The native tool-calling [ActionPlanner] strategy (A2 design note
 * §5.2, OpenAI wire format): the turn's [ProviderClient] is asked with
 * the core tools attached; a [StreamEvent.ToolCallReady] becomes a
 * [PlannedAction.CallTool], streamed text becomes
 * [PlannedAction.Respond].
 *
 * One instance serves ONE turn: the bridge/wiring constructs it with
 * the turn's [prompt] (and any [priorTurns] from earlier turns) because
 * the [ActionPlanner] contract passes only the event history, which
 * does not carry the user's prompt. Within the turn, the conversation
 * is rebuilt deterministically from that history on every call:
 * proposed calls become assistant tool-call turns, results become
 * tool-result turns.
 *
 * Parallel policy (§5.2): a response may carry several calls; they are
 * executed SEQUENTIALLY in index order — queued calls are served from
 * [pendingCalls] without a new provider request.
 *
 * Text is batched: tokens accumulate and surface as a single
 * [PlannedAction.Respond] (one `TextDelta` in the loop). Live
 * token-by-token streaming through [ActionPlanner] is a pre-A3 design
 * item; this class does not change the A1 contract.
 */
class NativeOpenAiPlanner(
    private val client: ProviderClient,
    private val systemPrompt: String,
    private val prompt: String,
    private val priorTurns: List<ChatTurn> = emptyList(),
) : ActionPlanner {

    private val pendingCalls = ArrayDeque<AiToolCall>()

    override suspend fun nextAction(
        context: TurnContext,
        history: List<AgentEvent>,
        tools: List<ToolSpec>,
    ): PlannedAction {
        pendingCalls.removeFirstOrNull()?.let { next ->
            return PlannedAction.CallTool(ToolCall(next.id, next.name, next.argumentsJson))
        }
        val request = ChatRequest(
            systemPrompt = systemPrompt,
            turns = priorTurns + ChatTurn(MessageRole.USER, prompt) + historyTurns(history),
            tools = tools.map { AiToolDefinition(it.name, it.description, it.parametersJsonSchema) },
        )
        val text = StringBuilder()
        var failure: AiError? = null
        var ready: List<AiToolCall> = emptyList()
        client.streamChat(request).collect { event ->
            when (event) {
                is StreamEvent.Token -> text.append(event.text)
                is StreamEvent.ToolCallReady -> ready = event.calls
                is StreamEvent.Failed -> failure = event.error
                else -> Unit
            }
        }
        failure?.let { throw ProviderCallException(it) }
        if (ready.isNotEmpty()) {
            pendingCalls.addAll(ready.drop(1))
            val first = ready.first()
            return PlannedAction.CallTool(ToolCall(first.id, first.name, first.argumentsJson))
        }
        return PlannedAction.Respond(text.toString())
    }

    /** Rebuilds this turn's conversation from its event history. */
    private fun historyTurns(history: List<AgentEvent>): List<ChatTurn> =
        history.mapNotNull { event ->
            when (event) {
                is AgentEvent.ToolCallProposed -> ChatTurn(
                    role = MessageRole.ASSISTANT,
                    content = "",
                    toolCalls = listOf(
                        AiToolCall(event.call.id, event.call.toolName, event.call.argumentsJson)
                    ),
                )
                is AgentEvent.ToolResultReceived -> ChatTurn(
                    role = MessageRole.USER,
                    content = event.result.output,
                    toolCallId = event.result.callId,
                )
                is AgentEvent.TextDelta -> ChatTurn(MessageRole.ASSISTANT, event.text)
                else -> null
            }
        }
}
