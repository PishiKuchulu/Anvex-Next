package com.anvex.core.agent

/**
 * Everything a turn needs to run, in pure-JVM types only. Provider and
 * model are **opaque ids** chosen by the settings layer (`:core:android`);
 * resolving them into endpoints, credentials and capabilities is the
 * planner/bridge's job, never this contract's.
 *
 * [projectId] is null in direct-chat mode (no project workspace, file tools
 * unavailable) — the same distinction `ChatSession`'s `directChat` flag
 * carries today.
 */
data class TurnContext(
    val sessionId: String,
    val projectId: String? = null,
    val providerId: String,
    val modelId: String,
)
