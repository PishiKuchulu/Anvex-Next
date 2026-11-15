package com.anvex.core.ai

import com.anvex.core.model.ProviderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A2c (design note §5.3): how much is known about a provider kind's
 * native tool-calling support. Capability *knowledge* lives here in
 * `:core:ai`; *selection* lives in `:core:agent`'s router; *resolution*
 * (settings, stored override) lives in `:core:android` — neither
 * `:core:ai` nor `:core:agent` ever reads settings.
 */
enum class ToolCallingSupport {
    /** The first-party client implements tool-calling for this kind. */
    NATIVE,

    /**
     * The wire format can carry tools but this endpoint's support is
     * unproven (aggregators, account tiers, local servers). The router
     * attempts native with the bounded runtime downgrade armed (§5.3).
     */
    WIRE_CAPABLE_UNKNOWN,

    /** No tool-calling attempt: the router goes straight to bolt text. */
    NONE,
}

/**
 * A2c (design note §5.3, Q2): the user's explicit tool-calling mode
 * for one provider, resolved by the settings layer and handed to the
 * router as part of a capability snapshot — a *value*, never a
 * settings read inside `:core:ai` or `:core:agent`. Persistence
 * (settings map keyed by provider id) lands with the A3 wiring; this
 * stage ships the type only.
 */
enum class ToolCallingOverride {
    /** Follow the static [ToolCallingSupport] table. */
    AUTO,

    /** Use the native planner even when the table says otherwise. */
    FORCE_NATIVE,

    /** Use the bolt-text planner even when the table says native. */
    FORCE_BOLT,
}

/**
 * Phase 4 capability model: a feature is offered in the UI only when the provider kind
 * supports it. Unsupported kinds keep the existing agent tools instead.
 */
object ProviderCapabilities {
    fun webSearch(kind: ProviderKind): Boolean =
        kind == ProviderKind.ANTHROPIC || kind == ProviderKind.GEMINI

    fun imageGeneration(kind: ProviderKind): Boolean =
        kind == ProviderKind.OPENAI || kind == ProviderKind.GEMINI

    /**
     * A2c (design note §5.3): the static tool-calling table — no
     * probing. Conservative by default (the `GeminiCapabilities`
     * precedent): a kind the table does not name gets [NONE]. The
     * `when` is exhaustive, so a future kind cannot be added without
     * classifying it here.
     */
    fun toolCalling(kind: ProviderKind): ToolCallingSupport = when (kind) {
        // First-party kinds, plus their custom-compatible counterparts
        // (the factory routes those to the same clients).
        ProviderKind.OPENAI,
        ProviderKind.ANTHROPIC,
        ProviderKind.GEMINI,
        ProviderKind.CUSTOM_OPENAI,
        ProviderKind.CUSTOM_ANTHROPIC -> ToolCallingSupport.NATIVE
        // OpenAI-wire aggregators and account tiers of unknown
        // ability, plus local servers (note §5.3).
        ProviderKind.OPENROUTER,
        ProviderKind.DEEPSEEK,
        ProviderKind.GROQ,
        ProviderKind.MISTRAL,
        ProviderKind.ZAI_ACCOUNT,
        ProviderKind.OPENCODE_FREE,
        ProviderKind.OPENCODE_ZEN,
        ProviderKind.OLLAMA,
        ProviderKind.LMSTUDIO -> ToolCallingSupport.WIRE_CAPABLE_UNKNOWN
        // Retired kinds, kept only so legacy Room rows decode; not
        // named by the §5.3 table, so "anything else" applies.
        ProviderKind.CLINE,
        ProviderKind.ANTIGRAVITY -> ToolCallingSupport.NONE
    }
}

/** Phase 4.3: image generation through OpenAI-style and Gemini image endpoints. */
object ImageGeneration {

    data class Image(val bytes: ByteArray, val mimeType: String)

    suspend fun generate(
        runtime: ProviderRuntimeConfig,
        prompt: String,
        http: OkHttpClient = defaultHttpClient(),
    ): Result<Image> = withContext(Dispatchers.IO) {
        runCatching {
            when (runtime.config.kind) {
                ProviderKind.OPENAI -> openAi(runtime, prompt, http)
                ProviderKind.GEMINI -> gemini(runtime, prompt, http)
                else -> error("This provider cannot generate images. Use an OpenAI or Gemini provider.")
            }
        }
    }

    private fun openAi(runtime: ProviderRuntimeConfig, prompt: String, http: OkHttpClient): Image {
        val base = runtime.config.baseUrl.trimEnd('/')
        val url = if (base.endsWith("/v1")) "$base/images/generations" else "$base/v1/images/generations"
        val model = runtime.config.model.takeIf { it.contains("image") || it.startsWith("dall-e") } ?: "gpt-image-1"
        val body = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            put("n", 1)
            put("size", "1024x1024")
        }
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${runtime.apiKey}")
            .header("Content-Type", "application/json")
        for ((k, v) in runtime.config.extraHeaders) builder.header(k, v)
        val root = execute(http, builder.post(body.toString().toRequestBody("application/json".toMediaType())).build())
        val item = root["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: error("No image in the response.")
        item["b64_json"]?.jsonPrimitive?.contentOrNull?.let {
            return Image(java.util.Base64.getDecoder().decode(it), "image/png")
        }
        val remote = item["url"]?.jsonPrimitive?.contentOrNull ?: error("No image in the response.")
        val bytes = http.newCall(Request.Builder().url(remote).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) error("Image download failed (HTTP ${resp.code}).")
            resp.body?.bytes() ?: error("Empty image download.")
        }
        return Image(bytes, "image/png")
    }

    private fun gemini(runtime: ProviderRuntimeConfig, prompt: String, http: OkHttpClient): Image {
        val base = runtime.config.baseUrl.trimEnd('/')
        val model = runtime.config.model.takeIf { it.contains("image") } ?: "gemini-2.5-flash-image"
        val root = if (base.contains("/v1beta")) base else "$base/v1beta"
        val body = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", prompt) }) })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add("TEXT"); add("IMAGE") })
            })
        }
        val req = Request.Builder()
            .url("$root/models/$model:generateContent")
            .header("x-goog-api-key", runtime.apiKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val json = execute(http, req)
        val parts = json["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray ?: emptyList()
        for (part in parts) {
            val inline = (part as? JsonObject)?.get("inlineData") as? JsonObject ?: continue
            val data = inline["data"]?.jsonPrimitive?.contentOrNull ?: continue
            val mime = inline["mimeType"]?.jsonPrimitive?.contentOrNull ?: "image/png"
            return Image(java.util.Base64.getDecoder().decode(data), mime)
        }
        error("Gemini returned no image. Try a more specific description.")
    }

    private fun execute(http: OkHttpClient, req: Request): JsonObject =
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Provider returned HTTP ${resp.code}: ${text.take(200)}")
            Sse.json.parseToJsonElement(text).jsonObject
        }
}
