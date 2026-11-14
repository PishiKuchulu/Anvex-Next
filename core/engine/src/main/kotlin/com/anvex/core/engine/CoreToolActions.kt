package com.anvex.core.engine

/**
 * A2a-0 (§4 extraction, ADR-0003): the ONE shared mapping from a core
 * tool call to the [BoltAction] the safety policy judges — pure
 * strings in, action out. Consumed by PiToolMapping (which flattens
 * its JSONObject into these arguments) and by the native agent loop
 * (A2a's TurnRunner, which parses ToolCall.argumentsJson); field
 * extraction from each consumer's wire shape stays with the consumer.
 * Before this extraction the mapping existed only inside PiToolMapping,
 * and the new loop would have grown a second, divergent copy.
 *
 * Only the four core tools are shared. Pi-only tools (grep/find/ls/mcp)
 * keep their PiToolMapping branches; anything else reaching this
 * primitive maps to [BoltAction.UnknownAction] — fail-closed, exactly
 * as PiToolMapping's tail has always done.
 */
object CoreToolActions {

    /**
     * @param path the tool's target path (write/edit/read).
     * @param content for `write`, the file content; for `edit`, the
     *   MUTATION FINGERPRINT (see below); for an unknown tool, the raw
     *   arguments JSON, preserved as the UnknownAction content exactly
     *   as PiToolMapping has always passed its full input JSON.
     * @param command for `bash`, the shell command.
     *
     * **Caller contract for `edit` (sign-off addendum (b),
     * security-relevant):** the caller MUST pass `content =` the FULL
     * argumentsJson — deterministic, covering the complete mutation
     * (oldText + newText), with NO truncation — and `path =` the file
     * path. This string is the fingerprint that feeds durable grants
     * in CommandApprovalService: a weak or truncated fingerprint means
     * grant collisions (an approval granted for one edit silently
     * covering a different one). The M-02 lesson applies: the
     * fingerprint must cover the whole mutation. (Pi's caller passes
     * its edits-array string, which stands in for the resulting
     * content the same way.)
     */
    fun toBoltAction(
        toolName: String,
        path: String? = null,
        content: String? = null,
        command: String? = null,
    ): BoltAction = when (toolName) {
        "write" -> BoltAction.FileAction(content = content ?: "", filePath = path ?: "")
        "edit" -> BoltAction.FileAction(content = content ?: "", filePath = path ?: "")
        "bash" -> BoltAction.ShellAction(content = command ?: "")
        "read" -> BoltAction.ToolAction("read", path ?: "")
        else -> BoltAction.UnknownAction(toolName, content ?: "")
    }
}
