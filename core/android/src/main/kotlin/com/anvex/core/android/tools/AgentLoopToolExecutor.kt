package com.anvex.core.android.tools

import com.anvex.core.agent.CoreTools
import com.anvex.core.agent.ToolCall
import com.anvex.core.agent.ToolExecutor
import com.anvex.core.agent.ToolResult
import com.anvex.core.agent.TurnContext
import com.anvex.core.android.runtime.AndroidShellRuntime
import com.anvex.core.android.runtime.ShellOrigin
import com.anvex.core.engine.ActionDispatcher
import com.anvex.core.engine.BoltAction
import com.anvex.core.engine.PathSanitizer
import com.anvex.core.engine.ProjectFileSystem
import com.anvex.core.engine.SensitivePaths
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * The `:core:android` [ToolExecutor] of the new native loop (A2 design
 * note §3). The gate (assessment + the loop's single approval) already
 * ran in `TurnRunner`; this class owns ONLY the side effects, reusing
 * the existing execution paths — no parallel policy (ADR-0003):
 *
 * - `read`/`write`/`edit` run through [ActionDispatcher]
 *   (`applyFileAction` + its `readFile`), whose execution-layer guards
 *   (protected paths, journaling) are defense-in-depth.
 * - `bash` runs through [AndroidShellRuntime] with [ShellOrigin.AI].
 *
 * Project resolution mirrors `AgentTaskBridge`'s seams: [fsProvider]
 * resolves a project id to its abstract filesystem (production wires
 * `ProjectRepository.fileSystemForAsync`), [shellRootProvider] to the
 * REAL app-private root (production wires
 * `AndroidRuntimeGateway.realRootDirectory` — SAF workspaces
 * deliberately resolve to null there, so shell fails closed).
 *
 * Fail-closed rules:
 * - Direct chat (`projectId == null`): ALL FOUR tools fail with an
 *   error result and zero storage access (sign-off Q1).
 * - An unresolvable workspace fails every call that needs it.
 * - SENSITIVE READ: the loop's assessment marks a read-only
 *   `ToolAction` SAFE, so the gate never prompts for `read .env`.
 *   Execution therefore refuses sensitive/protected paths itself —
 *   the [AgentToolExecutor.read] / `ActionDispatcher.readFile`
 *   pattern — and never surfaces a secret on the policy's say-so.
 */
class AgentLoopToolExecutor(
    private val fsProvider: suspend (String) -> ProjectFileSystem?,
    private val shellRootProvider: suspend (String) -> File?,
) : ToolExecutor {

    override suspend fun execute(context: TurnContext, call: ToolCall): ToolResult {
        val projectId = context.projectId
            ?: return error(call, "No project workspace: tools are unavailable in direct chat.")
        return try {
            when (call.toolName) {
                CoreTools.READ -> read(projectId, call)
                CoreTools.WRITE -> write(projectId, call)
                CoreTools.EDIT -> edit(projectId, call)
                CoreTools.BASH -> bash(projectId, call)
                else -> error(call, "Unknown tool: ${call.toolName}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error(call, "${call.toolName} failed: ${e.message?.take(200) ?: e::class.simpleName}")
        }
    }

    // ------------------------------------------------------------------ tools

    private suspend fun read(projectId: String, call: ToolCall): ToolResult {
        val args = arguments(call) ?: return error(call, "Tool arguments must be a JSON object.")
        val rawPath = args.string("path") ?: return error(call, "Missing 'path' argument.")
        val safe = PathSanitizer.sanitizeOrNull(rawPath.trim()) ?: return error(call, "Invalid path.")
        if (SensitivePaths.isProtectedPath(safe)) {
            return error(call, "Refused: '$safe' is a protected path.")
        }
        if (SensitivePaths.isSensitive(safe) && !SensitivePaths.isReadableExtractedAttachment(safe)) {
            return error(call, "Refused: '$safe' is a protected file (secrets/app metadata are never exposed to tools).")
        }
        val fs = workspace(projectId, call) ?: return error(call, WORKSPACE_UNAVAILABLE)
        val result = ActionDispatcher(fs).readFile(safe)
        return if (result.success) {
            ToolResult(call.id, call.toolName, result.output ?: "")
        } else {
            error(call, result.error ?: "Read failed.")
        }
    }

    private suspend fun write(projectId: String, call: ToolCall): ToolResult {
        val args = arguments(call) ?: return error(call, "Tool arguments must be a JSON object.")
        val rawPath = args.string("path") ?: return error(call, "Missing 'path' argument.")
        val content = args.string("content") ?: return error(call, "Missing 'content' argument.")
        val safe = PathSanitizer.sanitizeOrNull(rawPath.trim()) ?: return error(call, "Invalid path.")
        if (SensitivePaths.isProtectedPath(safe)) {
            return error(call, "Refused: '$safe' is a protected path.")
        }
        val fs = workspace(projectId, call) ?: return error(call, WORKSPACE_UNAVAILABLE)
        // approvalGranted: the loop's ApprovalGateway already granted
        // approval for this exact call (after assessing this exact
        // FileAction); the dispatcher's own size assessment still runs
        // as defense-in-depth but must not re-ask.
        val result = ActionDispatcher(fs).applyFileAction(
            BoltAction.FileAction(content = content, filePath = safe),
            approvalGranted = true,
        )
        return if (result.success) {
            ToolResult(call.id, call.toolName, result.output ?: "Wrote $safe.")
        } else {
            error(call, result.error ?: "Write failed.")
        }
    }

    private suspend fun edit(projectId: String, call: ToolCall): ToolResult {
        val args = arguments(call) ?: return error(call, "Tool arguments must be a JSON object.")
        val rawPath = args.string("path") ?: return error(call, "Missing 'path' argument.")
        val oldText = args.string("oldText") ?: return error(call, "Missing 'oldText' argument.")
        val newText = args.string("newText") ?: return error(call, "Missing 'newText' argument.")
        if (oldText.isEmpty()) return error(call, "The 'oldText' argument must not be empty.")
        val safe = PathSanitizer.sanitizeOrNull(rawPath.trim()) ?: return error(call, "Invalid path.")
        if (SensitivePaths.isProtectedPath(safe)) {
            return error(call, "Refused: '$safe' is a protected path.")
        }
        val fs = workspace(projectId, call) ?: return error(call, WORKSPACE_UNAVAILABLE)
        val dispatcher = ActionDispatcher(fs)
        // The read inherits readFile's own sensitive-path refusal.
        val readResult = dispatcher.readFile(safe)
        if (!readResult.success) return error(call, readResult.error ?: "Read failed.")
        val current = readResult.output ?: return error(call, "Read failed.")
        val occurrences = current.split(oldText).size - 1
        if (occurrences != 1) {
            return error(
                call,
                if (occurrences == 0) {
                    "The 'oldText' span was not found in '$safe' — the file was not changed."
                } else {
                    "The 'oldText' span occurs $occurrences times in '$safe'; it must be unique — the file was not changed."
                },
            )
        }
        val result = dispatcher.applyFileAction(
            BoltAction.FileAction(content = current.replace(oldText, newText), filePath = safe),
            approvalGranted = true,
        )
        return if (result.success) {
            ToolResult(call.id, call.toolName, result.output ?: "Edited $safe.")
        } else {
            error(call, result.error ?: "Edit failed.")
        }
    }

    private suspend fun bash(projectId: String, call: ToolCall): ToolResult {
        val args = arguments(call) ?: return error(call, "Tool arguments must be a JSON object.")
        val command = args.string("command") ?: return error(call, "Missing 'command' argument.")
        val root = shellRootProvider(projectId)
            ?: return error(call, "A real app-private workspace is required for shell execution.")
        // SINGLE APPROVAL (design note §3): the loop's ApprovalGateway
        // is the only user-facing prompt, and it already approved THIS
        // command (the gate assessed the same command string). The
        // runtime's approval seam is therefore answered with the
        // already-granted decision — the same non-interactive shape the
        // durable task path passes (AgentTaskBridge) — while the
        // runtime's own assessment and the AI allowlist escalation
        // still run unchanged.
        val runtime = AndroidShellRuntime(root) { true }
        val result = runtime.execute(command, origin = ShellOrigin.AI)
        return when {
            result.success -> ToolResult(call.id, call.toolName, result.output)
            result.approvalRequired ->
                error(call, "Approval required: ${result.error ?: "command not approved"}")
            else -> error(call, result.error ?: result.output.ifBlank { "Command failed." })
        }
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun workspace(projectId: String, call: ToolCall): ProjectFileSystem? =
        fsProvider(projectId)

    private fun arguments(call: ToolCall): JsonObject? =
        try {
            Json.parseToJsonElement(call.argumentsJson) as? JsonObject
        } catch (_: Exception) {
            null
        }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun error(call: ToolCall, message: String): ToolResult =
        ToolResult(call.id, call.toolName, message, isError = true)

    private companion object {
        const val WORKSPACE_UNAVAILABLE = "Project workspace is unavailable."
    }
}
