package com.anvex.core.ai

import com.anvex.core.model.ModelInfo
import com.anvex.core.model.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI wire protocol client — covers OpenAI, OpenRouter, DeepSeek, Mistral,
 * Groq, Ollama (/v1), LM Studio, and arbitrary user-configured
 * OpenAI-compatible endpoints (brief §10).
 */
class OpenAiCompatibleClient(
    private val runtime: ProviderRuntimeConfig,
    private val http: OkHttpClient = defaultHttpClient(),
) : ProviderClient {

    override val kind = runtime.config.kind

    private val json = Sse.json
    private val base = runtime.config.baseUrl.trimEnd('/')
    private val apiKey = runtime.apiKey

    private fun requestBuilder(): Request.Builder {
        val b = Request.Builder()
            .header("Content-Type", "application/json")
        if (apiKey.isNotBlank()) b.header("Authorization", "Bearer $apiKey")
        for ((k, v) in runtime.config.extraHeaders) b.header(k, v)
        return b
    }

    override fun streamChat(request: ChatRequest): Flow<StreamEvent> = flow {
        // A2: tool-result turns also carry MessageRole.USER (see ChatTurn)
        // but are not user messages — attachments belong to real user turns.
        val lastUserIndex = request.turns.indexOfLast {
            it.role == com.anvex.core.model.MessageRole.USER && it.toolCallId == null
        }
        val messages = buildJsonArray {
            add(buildJsonObject {
                put("role", "system"); put("content", request.systemPrompt)
            })
            request.turns.forEachIndexed { index, turn ->
                val isLastUser = turn.role == com.anvex.core.model.MessageRole.USER && index == lastUserIndex
                add(buildJsonObject {
                    // A2 (§5.2): native tool-calling turn shapes. Plain
                    // turns render exactly as before (byte-identical
                    // no-tools requests are a regression lock).
                    when {
                        turn.toolCallId != null -> {
                            put("role", "tool")
                            put("tool_call_id", turn.toolCallId)
                            put("content", turn.content)
                        }
                        turn.toolCalls.isNotEmpty() -> {
                            put("role", "assistant")
                            put("content", kotlinx.serialization.json.JsonNull)
                            put("tool_calls", buildJsonArray {
                                turn.toolCalls.forEach { call ->
                                    add(buildJsonObject {
                                        put("id", call.id)
                                        put("type", "function")
                                        put("function", buildJsonObject {
                                            put("name", call.name)
                                            put("arguments", call.argumentsJson)
                                        })
                                    })
                                }
                            })
                        }
                        else -> {
                    put("role", if (turn.role == com.anvex.core.model.MessageRole.USER) "user" else "assistant")
                    if (isLastUser && request.attachments.isNotEmpty()) {
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", turn.content) })
                            request.attachments.forEach { a ->
                                when {
                                    a.isImage && a.base64 != null -> add(buildJsonObject {
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject { put("url", "data:${a.mimeType};base64,${a.base64}") })
                                    })
                                    !a.text.isNullOrBlank() -> add(buildJsonObject {
                                        put("type", "text"); put("text", "\n[Attachment: ${a.name}]\n${a.text}")
                                    })
                                }
                            }
                        })
                    } else put("content", turn.content)
                        }
                    }
                })
            }
        }
        val effort = effortFor(request.thinkingLevel)
        // Attempt 0 sends the level mapped to this provider's vocabulary.
        // If the endpoint rejects the knob with HTTP 400 before any token
        // streams, attempt 1 retries the identical request without it, so
        // a provider with no reasoning parameter still chats instead of
        // failing. A failure after tokens started streaming is never
        // retried (it would duplicate tokens).
        var outcome = runAttempt(request, messages, effort, sendEffort = true, sendUsageOptions = true)
        // M-16: the old code retried EVERY pre-token 400 without effort —
        // a bad model or an over-long context burned a second attempt and
        // replaced the real error. Retry only when the 400 plausibly rejects
        // a request parameter (reasoning_effort / stream_options / unknown
        // parameter); a blank message is retried because its cause is unknown.
        if (outcome.failure?.httpStatus == 400 && outcome.tokenCount == 0 &&
            isParameterRejection(outcome.failure?.message)
        ) {
            outcome = runAttempt(request, messages, effort, sendEffort = false, sendUsageOptions = false)
        }
        when {
            outcome.failure != null -> emit(StreamEvent.Failed(outcome.failure))
            outcome.sawDone || outcome.tokenCount > 0 || outcome.toolCallsEmitted ->
                emit(StreamEvent.Completed(outcome.finishReason, outcome.usage))
            else -> emit(StreamEvent.Failed(AiError(AiError.Kind.PROTOCOL, "Stream ended without content")))
        }
    }.flowOn(Dispatchers.IO)

    private data class AttemptOutcome(
        val failure: AiError?,
        val sawDone: Boolean,
        val tokenCount: Int,
        val finishReason: String?,
        val usage: Usage?,
        /** A2: a [StreamEvent.ToolCallReady] was emitted by this attempt. */
        val toolCallsEmitted: Boolean = false,
    )

    /** A2 (§5.2): streaming fragments of one tool call, accumulated per delta `index`. */
    private class ToolCallFragments {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    /** A tool definition's JSON-Schema string as a JSON object (empty object when unparseable). */
    private fun parseToolParameters(schema: String): JsonObject =
        try {
            json.parseToJsonElement(schema) as? JsonObject ?: buildJsonObject {}
        } catch (_: Exception) {
            buildJsonObject {}
        }

    /**
     * Provider vocabulary for this endpoint's reasoning knob. OpenAI and
     * OpenRouter use the OpenAI map; DeepSeek uses its low/high/max map
     * (plus the thinking toggle its docs pair with it); Groq uses the
     * capped map because it rejects out-of-set values with HTTP 400.
     * Every other OpenAI-protocol kind (Mistral, custom endpoints,
     * OpenCode, legacy local servers) gets the OpenAI map and relies on
     * the retry-without-effort fallback if the endpoint rejects it.
     */
    private fun effortFor(level: com.anvex.core.model.ThinkingLevel): String = when (runtime.config.kind) {
        com.anvex.core.model.ProviderKind.DEEPSEEK -> level.toDeepSeekEffort()
        com.anvex.core.model.ProviderKind.GROQ -> level.toGroqEffort()
        else -> level.toOpenAiEffort()
    }

    /**
     * One streaming attempt. Tokens and reasoning deltas are emitted as
     * they arrive; the outcome reports whether anything streamed, so the
     * caller can decide whether a retry is safe.
     */
    /** M-16: is this 400 plausibly a rejected request parameter (retryable) rather than a bad model / context / key? */
    private fun isParameterRejection(message: String?): Boolean {
        if (message.isNullOrBlank()) return true
        val m = message.lowercase()
        if (m.contains("model") && (m.contains("not found") || m.contains("does not exist"))) return false
        if (m.contains("context") && (m.contains("length") || m.contains("too long") || m.contains("too large"))) return false
        if (m.contains("api key") || m.contains("authentication") || m.contains("unauthorized")) return false
        return m.contains("reasoning") || m.contains("stream_options") || m.contains("unsupported") ||
            m.contains("unknown parameter") || m.contains("invalid parameter") ||
            m.contains("additional properties")
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<StreamEvent>.runAttempt(
        request: ChatRequest,
        messages: kotlinx.serialization.json.JsonArray,
        effort: String?,
        sendEffort: Boolean,
        sendUsageOptions: Boolean,
    ): AttemptOutcome {
        val body = buildJsonObject {
            put("model", runtime.config.model)
            put("messages", messages)
            // A2 (§5.2): native tool-calling. Only sent when tools are
            // offered — a no-tools body stays byte-identical (lock test).
            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    request.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", parseToolParameters(tool.parametersJsonSchema))
                            })
                        })
                    }
                })
                put("tool_choice", "auto")
            }
            put("stream", true)
            // M-16: without stream_options.include_usage, OpenAI/OpenRouter
            // never send usage, so token/cost accounting stays empty.
            if (sendUsageOptions) {
                put("stream_options", buildJsonObject { put("include_usage", true) })
            }
            request.temperature?.let { put("temperature", it) } ?: runtime.config.temperature?.let { put("temperature", it) }
            (request.maxTokens ?: runtime.config.maxTokens)?.let { put("max_tokens", it) }
            if (sendEffort && effort != null) {
                put("reasoning_effort", effort)
                if (runtime.config.kind == com.anvex.core.model.ProviderKind.DEEPSEEK) {
                    put("thinking", buildJsonObject { put("type", "enabled") })
                }
            }
        }

        val req = requestBuilder()
            .url("$base/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var finishReason: String? = null
        var usage: Usage? = null
        var failure: AiError? = null
        var sawDone = false
        var tokenCount = 0
        // A2 (§5.2): tool-call fragments accumulate per delta index
        // (id + name arrive on the first fragment, arguments concatenate).
        val pendingToolCalls = sortedMapOf<Int, ToolCallFragments>()

        val events = Sse.stream(http, req) { err -> failure = err }

        events.collect { frame ->
            if (frame.data == "[DONE]") {
                sawDone = true
                return@collect
            }
            val obj = parse(frame.data) ?: return@collect
            val choices = obj["choices"]?.jsonArrayOrNull
            val choice = choices?.firstOrNull()?.jsonObjectOrNull
            if (choice != null) {
                // Some gateways answer a "streaming" request with one
                // non-streamed completion chunk whose content sits in
                // `message`, not `delta` — accept both shapes.
                val delta = choice["delta"]?.jsonObjectOrNull ?: choice["message"]?.jsonObjectOrNull
                val content = delta?.get("content")?.jsonPrimitiveOrNull?.contentOrNull
                if (!content.isNullOrEmpty()) {
                    tokenCount++
                    emit(StreamEvent.Token(content))
                }
                // v4.4 (architect feature 1): OpenAI-compatible reasoning streams —
                // DeepSeek-R1/GLM-style `reasoning_content` (alias: `reasoning`).
                val reasoning = delta?.get("reasoning_content")?.jsonPrimitiveOrNull?.contentOrNull
                    ?: delta?.get("reasoning")?.jsonPrimitiveOrNull?.contentOrNull
                if (!reasoning.isNullOrEmpty()) emit(StreamEvent.Reasoning(reasoning))
                delta?.get("tool_calls")?.jsonArrayOrNull?.forEach { el ->
                    val callObj = el.jsonObjectOrNull ?: return@forEach
                    val index = callObj["index"]?.jsonPrimitiveOrNull?.contentOrNull?.toIntOrNull()
                        ?: return@forEach
                    val acc = pendingToolCalls.getOrPut(index) { ToolCallFragments() }
                    callObj["id"]?.jsonPrimitiveOrNull?.contentOrNull?.let { acc.id = it }
                    val fn = callObj["function"]?.jsonObjectOrNull
                    fn?.get("name")?.jsonPrimitiveOrNull?.contentOrNull?.let { acc.name = it }
                    fn?.get("arguments")?.jsonPrimitiveOrNull?.contentOrNull?.let { acc.arguments.append(it) }
                }
                choice["finish_reason"]?.jsonPrimitiveOrNull?.contentOrNull?.let { finishReason = it }
            }
            usage = obj["usage"]?.let { parseUsage(it) } ?: usage
        }
        // A2 (§5.2): on finish_reason "tool_calls" — or stream end with
        // fragments pending — the assembled calls are emitted ONCE as
        // ToolCallReady (before the caller's Completed). Assembled
        // arguments that are not a valid JSON object fail the turn with
        // PROTOCOL: a malformed call is never silently emitted.
        var toolCallsEmitted = false
        if (failure == null && pendingToolCalls.isNotEmpty()) {
            val assembled = pendingToolCalls.values.map { acc ->
                AiToolCall(
                    id = acc.id ?: "",
                    name = acc.name ?: "",
                    argumentsJson = acc.arguments.toString().ifBlank { "{}" },
                )
            }
            val malformed = assembled.firstOrNull { call ->
                call.name.isBlank() || try {
                    json.parseToJsonElement(call.argumentsJson) !is JsonObject
                } catch (_: Exception) {
                    true
                }
            }
            if (malformed != null) {
                failure = AiError(
                    AiError.Kind.PROTOCOL,
                    "Tool call '${malformed.name}' returned malformed arguments JSON",
                )
            } else {
                emit(StreamEvent.ToolCallReady(assembled))
                toolCallsEmitted = true
            }
        }
        return AttemptOutcome(failure, sawDone, tokenCount, finishReason, usage, toolCallsEmitted)
    }

    override suspend fun listModels(): List<ModelInfo> = executeModelsCall()

    private suspend fun executeModelsCall(): List<ModelInfo> {
        val req = requestBuilder().url("$base/models").get().build()
        return makeCall(http, req) { bodyText ->
            val root = parse(bodyText) ?: return@makeCall emptyList()
            val data = root["data"]?.jsonArrayOrNull ?: return@makeCall emptyList()
            data.mapNotNull { el ->
                val o = el.jsonObjectOrNull ?: return@mapNotNull null
                val id = o["id"]?.jsonPrimitiveOrNull?.contentOrNull ?: return@mapNotNull null
                ModelInfo(id = id, displayName = o["display_name"]?.jsonPrimitiveOrNull?.contentOrNull ?: id)
            }
        }
    }

    override suspend fun validateConnection(): ValidationResult {
        val t0 = System.currentTimeMillis()
        return try {
            val models = executeModelsCall()
            ValidationResult(
                ok = true,
                latencyMs = System.currentTimeMillis() - t0,
                message = "Connected. ${models.size} models available.",
                modelsFound = models.size,
            )
        } catch (e: AiHttpException) {
            // Some OpenAI-compatible gateways don't implement GET /models.
            if (e.code == 404 || e.code == 405) {
                validateViaMinimalChat()
            } else {
                ValidationResult(false, System.currentTimeMillis() - t0, Sse.mapHttpError(e.code, e.body ?: "").message)
            }
        } catch (e: Exception) {
            ValidationResult(false, System.currentTimeMillis() - t0, "Network error: ${e.message ?: "unreachable"}")
        }
    }

    private suspend fun validateViaMinimalChat(): ValidationResult {
        val t0 = System.currentTimeMillis()
        return try {
            probeChatEndpoint(http)
            ValidationResult(true, System.currentTimeMillis() - t0, "Connected (chat endpoint responded).")
        } catch (e: AiHttpException) {
            // Keep the upstream body visible (sanitized to error.message) — never
            // forged, never hidden.
            val upstream = e.body?.let { Sse.extractErrorMessage(it) } ?: Sse.mapHttpError(e.code, e.body ?: "").message
            ValidationResult(false, System.currentTimeMillis() - t0, upstream)
        } catch (e: Exception) {
            ValidationResult(false, System.currentTimeMillis() - t0, "Network error: ${e.message ?: "unreachable"}")
        }
    }

    /**
     * One-token chat probe over this client's base URL and headers. Throws
     * [AiHttpException] with the exact upstream status/body when the endpoint
     * refuses; returns normally on 2xx. Shared with [OpenCodeClient], which uses
     * it to report the free-tier gate state honestly.
     */
    internal suspend fun probeChatEndpoint(http: OkHttpClient) {
        val body = buildJsonObject {
            put("model", runtime.config.model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", "ping") })
            })
            put("max_tokens", 1)
        }
        val req = requestBuilder()
            .url("$base/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        makeCall(http, req) { "OK" }
    }

    private fun parse(text: String): JsonObject? = try {
        json.parseToJsonElement(text).jsonObjectOrNull
    } catch (_: Exception) {
        null
    }

    private fun parseUsage(el: kotlinx.serialization.json.JsonElement): Usage? {
        val o = el.jsonObjectOrNull ?: return null
        return Usage(
            promptTokens = o["prompt_tokens"]?.jsonPrimitiveOrNull?.longOrNull ?: 0,
            completionTokens = o["completion_tokens"]?.jsonPrimitiveOrNull?.longOrNull ?: 0,
            totalTokens = o["total_tokens"]?.jsonPrimitiveOrNull?.longOrNull ?: 0,
        )
    }
}

class AiHttpException(val code: Int, val body: String?) : Exception("HTTP $code")

/** Non-streaming helper used by listModels/validate (suspend, IO-dispatched). */
internal suspend fun <T> makeCall(http: OkHttpClient, request: Request, parse: (String) -> T): T =
    kotlinx.coroutines.withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw AiHttpException(resp.code, text)
            parse(text)
        }
    }
