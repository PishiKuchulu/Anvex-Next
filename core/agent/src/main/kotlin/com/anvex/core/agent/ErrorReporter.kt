package com.anvex.core.agent

/**
 * The single funnel for unexpected failures in new code (`ARCHITECTURE.md`
 * — Error policy). New code returns typed results for expected failures and
 * reports the unexpected through one [ErrorReporter] instead of the legacy
 * `runCatching { … }.getOrNull()` pattern, which is forbidden in new code.
 * Implementations live outside `:core:agent` (logging/reporting backends);
 * this contract is introduced with the A1 `:core:agent` contracts.
 */
fun interface ErrorReporter {
    fun report(error: Throwable, context: String)
}
