package com.anvex.core.agent

/**
 * File-based progress/memory for the loop (ADR-0002): progress notes and
 * memory live in files (project or app-private), not in hidden process
 * state, so they survive process death and stay inspectable. This is the
 * storage contract only — the file layout and keys are the implementation's
 * concern (`:core:android` bridges own the actual files).
 */
interface ProgressStore {

    /** Returns the note stored under [key], or null when none exists. */
    suspend fun load(key: String): String?

    /** Stores [content] under [key], replacing any previous note. */
    suspend fun save(key: String, content: String)
}
