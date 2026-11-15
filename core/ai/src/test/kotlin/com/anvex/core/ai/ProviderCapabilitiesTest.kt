package com.anvex.core.ai

import com.anvex.core.model.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A2c (design note §5.3): the static tool-calling capability table.
 * Every [ProviderKind] value is classified explicitly — the expected
 * map below must name each entry, so a future kind cannot slip in
 * unclassified (the `when` in [ProviderCapabilities.toolCalling] is
 * exhaustive for the same reason).
 */
class ProviderCapabilitiesTest {

    private val expected: Map<ProviderKind, ToolCallingSupport> = mapOf(
        // First-party kinds + their custom-compatible counterparts,
        // which the factory routes to the same clients.
        ProviderKind.OPENAI to ToolCallingSupport.NATIVE,
        ProviderKind.ANTHROPIC to ToolCallingSupport.NATIVE,
        ProviderKind.GEMINI to ToolCallingSupport.NATIVE,
        ProviderKind.CUSTOM_OPENAI to ToolCallingSupport.NATIVE,
        ProviderKind.CUSTOM_ANTHROPIC to ToolCallingSupport.NATIVE,
        // OpenAI-wire aggregators / account tiers of unknown ability,
        // and local servers: the wire can carry tools, support is
        // unproven — native attempt with the downgrade armed (§5.4).
        ProviderKind.OPENROUTER to ToolCallingSupport.WIRE_CAPABLE_UNKNOWN,
        ProviderKind.DEEPSEEK to ToolCallingSupport.WIRE_CAPABLE_UNKNOWN,
        ProviderKind.GROQ to ToolCallingSupport.WIRE_CAPABLE_UNKNOWN,
        ProviderKind.MISTRAL to ToolCallingSupport.WIRE_CAPABLE_UNKNOWN,
        ProviderKind.ZAI_ACCOUNT to ToolCallingSupport.WIRE_CAPABLE_UNKNOWN,
        ProviderKind.OPENCODE_FREE to ToolCallingSupport.WIRE_CAPABLE_UNKNOWN,
        ProviderKind.OPENCODE_ZEN to ToolCallingSupport.WIRE_CAPABLE_UNKNOWN,
        ProviderKind.OLLAMA to ToolCallingSupport.WIRE_CAPABLE_UNKNOWN,
        ProviderKind.LMSTUDIO to ToolCallingSupport.WIRE_CAPABLE_UNKNOWN,
        // Retired kinds (kept only so legacy Room rows decode): not
        // named by the §5.3 table, so the note's "anything else"
        // conservative default applies.
        ProviderKind.CLINE to ToolCallingSupport.NONE,
        ProviderKind.ANTIGRAVITY to ToolCallingSupport.NONE,
    )

    @Test fun everyProviderKindIsClassifiedPerTheTable() {
        assertEquals(
            "the expected map must cover every ProviderKind value",
            ProviderKind.entries.toSet(),
            expected.keys,
        )
        for ((kind, support) in expected) {
            assertEquals(kind.name, support, ProviderCapabilities.toolCalling(kind))
        }
    }

    @Test fun overrideTypeHasExactlyTheThreeModes() {
        assertEquals(
            listOf("AUTO", "FORCE_NATIVE", "FORCE_BOLT"),
            ToolCallingOverride.entries.map { it.name },
        )
    }
}
