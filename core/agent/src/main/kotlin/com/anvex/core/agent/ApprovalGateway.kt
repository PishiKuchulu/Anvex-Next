package com.anvex.core.agent

/** A request to approve one tool call before it executes. */
data class ApprovalRequest(
    val call: ToolCall,
    /** Why approval is required (safety-policy assessment, tool spec, …). */
    val reason: String,
)

/** The user's answer to an [ApprovalRequest]. */
sealed interface ApprovalDecision {
    data object Approved : ApprovalDecision
    data class Denied(val reason: String? = null) : ApprovalDecision
}

/**
 * The approval boundary between the loop and the user. Tools whose
 * [ToolSpec.requiresApproval] is set (notably `bash`) — and any call the
 * single `:core:engine` safety policy escalates (ADR-0003) — must obtain an
 * [ApprovalDecision.Approved] here before execution. Implementations live
 * in `:core:android`/`:app` (they own the UI); the loop only sees this
 * contract.
 */
interface ApprovalGateway {
    suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision
}
