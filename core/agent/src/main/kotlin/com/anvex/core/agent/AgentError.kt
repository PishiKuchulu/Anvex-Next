package com.anvex.core.agent

import com.anvex.core.ai.AiError

/**
 * Typed failure taxonomy for the agent loop (A2 design note §2, decision
 * D3). [AgentEvent.TurnFailed] carries one of these instead of a bare
 * string, so the `:core:android` bridge can route each failure to the
 * right UI surface: [Provider] → `UiState.error` (the [AiError] itself
 * travels); [Tool]/[Policy]/[Internal] → `UiState.engineError` (their
 * [message]).
 */
sealed interface AgentError {

    /** User-presentable summary of the failure. */
    val message: String

    /** A provider/client failure; [cause] is `:core:ai`'s typed error. */
    data class Provider(val cause: AiError) : AgentError {
        // Sign-off addendum (a): the contract field is user-presentable,
        // so it delegates to AiError.userTitle — the raw provider message
        // is diagnostic and stays available on [cause] for logs.
        override val message: String get() = cause.userTitle
    }

    /** One tool call failed in execution. */
    data class Tool(val toolName: String, override val message: String) : AgentError

    /** The single safety policy (ADR-0003) or the user denied the call. */
    data class Policy(val reason: String) : AgentError {
        override val message: String get() = reason
    }

    /** Anything else: loop bugs, contract violations, IO on progress files. */
    data class Internal(override val message: String) : AgentError
}
