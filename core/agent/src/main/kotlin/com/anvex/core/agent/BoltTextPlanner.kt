package com.anvex.core.agent

import com.anvex.core.ai.AiError
import com.anvex.core.ai.ChatRequest
import com.anvex.core.ai.ChatTurn
import com.anvex.core.ai.ProviderClient
import com.anvex.core.ai.StreamEvent
import com.anvex.core.engine.ActionCallbackData
import com.anvex.core.engine.BoltAction
import com.anvex.core.engine.BoltStreamParser
import com.anvex.core.engine.ParserCallbacks
import com.anvex.core.model.MessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * The bolt-text fallback [ActionPlanner] strategy (A2 design note
 * §5.5): for providers whose endpoints cannot take native tool calls,
 * the model answers in the established `<boltArtifact>/<boltAction>`
 * text protocol and the REPAIRED [BoltStreamParser] — reused
 * unchanged, never rewritten — turns the stream into actions.
 *
 * One instance serves ONE turn (same construction contract as
 * [NativeToolCallingPlanner]: the turn's [prompt] is baked in because
 * [ActionPlanner.nextAction] does not carry it).
 *
 * **Batch → incremental seam.** The parser emits N closed actions per
 * response, but the loop consumes ONE [PlannedAction] per
 * [nextAction] call. The planner therefore makes exactly **one
 * provider call per turn**: the first [nextAction] streams the whole
 * response through the parser, converts every closed action, and
 * queues the core [ToolCall]s; later calls drain the queue without
 * re-prompting, and an empty queue yields [PlannedAction.Respond]
 * with the response's prose. **Multi-round bolt** (feeding tool
 * results back for another provider round inside the same turn, the
 * way the native planner does) is a parity item to evaluate before
 * the A3 flag flip — deliberately NOT implemented here: in A2c a
 * bolt turn ends when its one response's actions are served.
 *
 * **Conversions** (§5.5): `FileAction` → `write{path, content}`;
 * `ShellAction` → `bash{command}`; `ToolAction("read")` →
 * `read{path}` (the parser's paged-read offset/limit are not carried
 * — the core read spec has no paging; parity item). Non-core actions
 * (other tool actions, artifact/start actions, unknown actions) have
 * no core [ToolCall] and surface as bracketed text lines in the
 * response text — nothing is silently dropped. A `FileAction` with
 * `append = true` merges into the immediately preceding queued
 * `write` for the same path (the bolt chunked-write protocol); the
 * core `write` tool replaces whole files, so serving an append chunk
 * as its own write would lose the earlier parts.
 *
 * **Flush policy** (stated explicitly, no reliance on parser
 * defaults — the [com.anvex.core.android.runtime.ProviderAgentPlanner]
 * precedent): on SUCCESSFUL stream completion a still-open action is
 * missing only its closing tag (a formatting omission), so the
 * planner flushes with `applyIncomplete = true`. The repaired
 * parser's invariant guarantees an unterminated shell/start action
 * is NEVER applied by that flush — only file (and other
 * non-executing) actions can be recovered. On failure the open
 * action is dropped via `takeOpenAction`, never flushed (H-06).
 *
 * The request carries **no tools**: bolt is the fallback for
 * endpoints that reject them; attaching the tool definitions would
 * re-trigger the very rejection this strategy avoids.
 */
class BoltTextPlanner(
    private val client: ProviderClient,
    private val systemPrompt: String,
    private val prompt: String,
    private val priorTurns: List<ChatTurn> = emptyList(),
) : ActionPlanner {

    private val pendingCalls = ArrayDeque<ToolCall>()

    /** The collected response text; null until the turn's one provider call ran. */
    private var responseText: String? = null

    override suspend fun nextAction(
        context: TurnContext,
        history: List<AgentEvent>,
        tools: List<ToolSpec>,
    ): PlannedAction {
        if (responseText == null) collect(history)
        pendingCalls.removeFirstOrNull()?.let { return PlannedAction.CallTool(it) }
        return PlannedAction.Respond(responseText.orEmpty())
    }

    /**
     * Runs the turn's single provider call: streams the response text
     * through the parser, queueing converted core calls and gathering
     * prose + non-core action renderings as the response text.
     */
    private suspend fun collect(history: List<AgentEvent>) {
        val textParts = mutableListOf<String>()
        val parser = BoltStreamParser(object : ParserCallbacks {
            override fun onActionClose(data: ActionCallbackData) {
                when (val action = data.action) {
                    is BoltAction.FileAction -> enqueueFile(data.actionId, action)
                    is BoltAction.ShellAction -> pendingCalls.addLast(
                        ToolCall(
                            id = "bolt-${data.actionId}",
                            toolName = CoreTools.BASH,
                            argumentsJson = buildJsonObject {
                                put("command", action.content)
                            }.toString(),
                        )
                    )
                    is BoltAction.ToolAction -> {
                        if (action.name == CoreTools.READ) {
                            pendingCalls.addLast(
                                ToolCall(
                                    id = "bolt-${data.actionId}",
                                    toolName = CoreTools.READ,
                                    argumentsJson = buildJsonObject {
                                        put("path", action.argument)
                                    }.toString(),
                                )
                            )
                        } else {
                            textParts += renderNonCore(action)
                        }
                    }
                    else -> textParts += renderNonCore(action)
                }
            }
        })
        val messageId = "bolt-turn-${UUID.randomUUID()}"
        val request = ChatRequest(
            systemPrompt = systemPrompt,
            turns = priorTurns + ChatTurn(MessageRole.USER, prompt) + historyTurns(history),
        )
        val fullText = StringBuilder()
        var failure: AiError? = null
        client.streamChat(request).collect { event ->
            when (event) {
                is StreamEvent.Token -> {
                    fullText.append(event.text)
                    val prose = parser.parse(messageId, fullText.toString())
                    if (prose.isNotEmpty()) textParts += prose
                }
                is StreamEvent.Failed -> failure = event.error
                else -> Unit
            }
        }
        failure?.let { error ->
            // H-06: a failed stream never applies its still-open
            // action — drop it explicitly, never flush.
            parser.takeOpenAction(messageId)
            throw ProviderCallException(error)
        }
        // Successful completion: recover a still-open file action
        // (missing close tag only). Shell/start can never be
        // recovered this way — the parser drops them under the
        // opt-in too (Pack 01-AMEND invariant).
        parser.flush(messageId, applyIncomplete = true)
        responseText = textParts.joinToString("").trim()
    }

    /**
     * Queues a `write` for [action], merging an `append` chunk into
     * the immediately preceding queued write for the same path (see
     * the class KDoc — the core write replaces whole files).
     */
    private fun enqueueFile(actionId: String, action: BoltAction.FileAction) {
        val previous = pendingCalls.lastOrNull()
        if (action.append && previous != null && previous.toolName == CoreTools.WRITE) {
            val previousArgs = parseArguments(previous.argumentsJson)
            if (previousArgs?.get("path")?.jsonPrimitive?.contentOrNull == action.filePath) {
                val mergedContent =
                    (previousArgs["content"]?.jsonPrimitive?.contentOrNull ?: "") + action.content
                pendingCalls.removeLast()
                pendingCalls.addLast(
                    ToolCall(
                        id = previous.id,
                        toolName = CoreTools.WRITE,
                        argumentsJson = buildJsonObject {
                            put("path", action.filePath)
                            put("content", mergedContent)
                        }.toString(),
                    )
                )
                return
            }
        }
        pendingCalls.addLast(
            ToolCall(
                id = "bolt-$actionId",
                toolName = CoreTools.WRITE,
                argumentsJson = buildJsonObject {
                    put("path", action.filePath)
                    put("content", action.content)
                }.toString(),
            )
        )
    }

    /** Non-core actions surface as text — bracketed, substance preserved, nothing dropped. */
    private fun renderNonCore(action: BoltAction): String = when (action) {
        is BoltAction.ToolAction ->
            "[tool action '${action.name}': ${action.argument}]\n"
        is BoltAction.ArtifactAction ->
            "[artifact action: ${action.format} '${action.outputName}']\n"
        is BoltAction.StartAction ->
            "[start action: ${action.content}]\n"
        is BoltAction.UnknownAction ->
            "[unknown action '${action.type}']\n"
        // Core actions are converted in the callback, never rendered.
        is BoltAction.FileAction, is BoltAction.ShellAction -> ""
    }

    /**
     * Rebuilds this turn's conversation from its event history (the
     * native planner's shape): prior tool calls render as the bolt
     * text the model itself would have written, results as user
     * turns carrying the tool output. This matters when the router
     * re-plans a turn through bolt after a native phase already
     * executed calls — the model must see what already happened.
     */
    private fun historyTurns(history: List<AgentEvent>): List<ChatTurn> =
        history.mapNotNull { event ->
            when (event) {
                is AgentEvent.ToolCallProposed -> ChatTurn(
                    role = MessageRole.ASSISTANT,
                    content = renderCallAsBoltText(event.call),
                )
                is AgentEvent.ToolResultReceived -> ChatTurn(
                    role = MessageRole.USER,
                    content = event.result.output,
                )
                is AgentEvent.TextDelta -> ChatTurn(MessageRole.ASSISTANT, event.text)
                else -> null
            }
        }

    private fun renderCallAsBoltText(call: ToolCall): String {
        val args = parseArguments(call.argumentsJson)
        fun field(name: String): String = args?.get(name)?.jsonPrimitive?.contentOrNull ?: ""
        return when (call.toolName) {
            CoreTools.WRITE ->
                "<boltArtifact><boltAction type=\"file\" filePath=\"${field("path")}\">" +
                    "${field("content")}</boltAction></boltArtifact>"
            CoreTools.BASH ->
                "<boltArtifact><boltAction type=\"shell\">${field("command")}</boltAction></boltArtifact>"
            CoreTools.READ ->
                "<boltArtifact><boltAction type=\"read\" path=\"${field("path")}\"></boltAction></boltArtifact>"
            else -> "[tool call ${call.toolName}: ${call.argumentsJson}]"
        }
    }

    private fun parseArguments(argumentsJson: String): JsonObject? =
        try {
            Json.parseToJsonElement(argumentsJson) as? JsonObject
        } catch (_: Exception) {
            null
        }
}
