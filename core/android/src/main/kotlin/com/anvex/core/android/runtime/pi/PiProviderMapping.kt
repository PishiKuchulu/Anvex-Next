package com.anvex.core.android.runtime.pi

import com.anvex.core.ai.ProviderRuntimeConfig
import com.anvex.core.model.ProviderKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps an Anvex provider onto a Pi `models.json` provider entry.
 *
 * M-1: the API key is embedded LITERALLY in the entry. Pi's schema
 * accepts a literal key (its template resolver passes a value with no
 * `$NAME` parts through unchanged), and models.json is written by the
 * driver only into the app-private / SensitivePaths-protected
 * `.anvex-pi` agent dir, whose contents Pi's own tools are hard-denied
 * from reading. The key must NEVER ride the process environment (the
 * old `$ANVEX_PI_API_KEY` interpolation): every bash child inherits
 * that environment, and a generic env dump would carry the key.
 *
 * Supported wire families (Pi api ids):
 *  - OpenAI-compatible (OpenAI, Custom OpenAI, OpenRouter, DeepSeek,
 *    Mistral, Groq) -> "openai-completions"
 *  - Anthropic / Custom Anthropic -> "anthropic-messages"
 *  - Gemini -> "google-generative-ai"
 * Everything else (account-login providers, retired local servers)
 * returns null — the Pi engine reports the provider as unsupported
 * instead of guessing a protocol.
 */
object PiProviderMapping {

    data class PiProvider(
        val providerName: String,
        val modelId: String,
        val modelsJson: JSONObject,
    )

    fun apiFor(kind: ProviderKind): String? = when (kind) {
        ProviderKind.OPENAI, ProviderKind.CUSTOM_OPENAI, ProviderKind.OPENROUTER,
        ProviderKind.DEEPSEEK, ProviderKind.MISTRAL, ProviderKind.GROQ,
        -> "openai-completions"
        ProviderKind.ANTHROPIC, ProviderKind.CUSTOM_ANTHROPIC -> "anthropic-messages"
        ProviderKind.GEMINI -> "google-generative-ai"
        else -> null
    }

    /** Stable Pi provider name derived from the Anvex provider id. */
    fun providerName(providerId: String): String =
        "anvex-" + providerId.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "default" }

    fun forRuntime(runtime: ProviderRuntimeConfig): PiProvider? {
        val config = runtime.config
        val api = apiFor(config.kind) ?: return null
        if (config.model.isBlank()) return null
        val name = providerName(config.id)
        val model = JSONObject()
            .put("id", config.model)
            .put("name", config.model)
            .put("reasoning", false)
            .put("input", JSONArray(listOf("text")))
            .put(
                "cost",
                JSONObject().put("input", 0).put("output", 0).put("cacheRead", 0).put("cacheWrite", 0),
            )
            .put("contextWindow", 128_000)
            .put("maxTokens", config.maxTokens ?: 8_192)
        val provider = JSONObject()
            .put("baseUrl", config.baseUrl)
            .put("api", api)
            .put("apiKey", runtime.apiKey)
            .put("models", JSONArray(listOf(model)))
        val root = JSONObject().put("providers", JSONObject().put(name, provider))
        return PiProvider(providerName = name, modelId = config.model, modelsJson = root)
    }
}
