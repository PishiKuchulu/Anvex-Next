package com.anvex.core.agent

/**
 * Description of one tool the loop may call. [parametersJsonSchema] is the
 * tool's parameter schema as a JSON Schema document (opaque string at this
 * layer — each planner strategy renders it into its provider's wire format).
 *
 * [requiresApproval] marks tools that must pass through [ApprovalGateway]
 * before execution; `bash` is approval-gated (ADR-0002), in addition to the
 * single `:core:engine` safety policy (ADR-0003).
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
    val requiresApproval: Boolean = false,
)

/** One concrete tool invocation proposed by the planner. */
data class ToolCall(
    val id: String,
    val toolName: String,
    val argumentsJson: String,
)

/** The outcome of executing a [ToolCall]. */
data class ToolResult(
    val callId: String,
    val toolName: String,
    val output: String,
    val isError: Boolean = false,
)

/**
 * The new loop's core tool set is exactly four tools (ADR-0002): read,
 * write, edit, bash. This is the minimal core surface, NOT a removal of
 * existing capabilities — web fetch/search, MCP, artifact generation and
 * Node/preview keep their current paths during the transition and are A2+
 * parity items.
 */
object CoreTools {
    const val READ = "read"
    const val WRITE = "write"
    const val EDIT = "edit"
    const val BASH = "bash"

    val READ_SPEC = ToolSpec(
        name = READ,
        description = "Read a file inside the project workspace.",
        parametersJsonSchema = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
    )
    val WRITE_SPEC = ToolSpec(
        name = WRITE,
        description = "Create or overwrite a file inside the project workspace.",
        parametersJsonSchema = """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""",
    )
    val EDIT_SPEC = ToolSpec(
        name = EDIT,
        description = "Replace an exact text span inside a workspace file.",
        parametersJsonSchema = """{"type":"object","properties":{"path":{"type":"string"},"oldText":{"type":"string"},"newText":{"type":"string"}},"required":["path","oldText","newText"]}""",
    )
    val BASH_SPEC = ToolSpec(
        name = BASH,
        description = "Run a shell command in the project workspace (approval-gated).",
        parametersJsonSchema = """{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}""",
        requiresApproval = true,
    )

    /** All four core specs, in canonical order. */
    val ALL: List<ToolSpec> = listOf(READ_SPEC, WRITE_SPEC, EDIT_SPEC, BASH_SPEC)
}
