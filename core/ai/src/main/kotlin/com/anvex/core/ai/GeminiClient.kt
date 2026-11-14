package com.anvex.core.ai

import com.anvex.core.model.MessageRole
import com.anvex.core.model.ModelInfo
import com.anvex.core.model.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Google Gemini client — `:streamGenerateContent?alt=sse` streaming and
 * `:models` listing. The API key travels as a query parameter (Google's
 * documented transport) or x-goog-api-key header.
 */
class GeminiClient(
    private val runtime: ProviderRuntimeConfig,
    private val http: OkHttpClient = defaultHttpClient(),
) : ProviderClient {

    override val kind = runtime.config.kind

    private val json = Sse.json
    private val base = runtime.config.baseUrl.trimEnd('/')

    override fun streamChat(request: ChatRequest): Flow<StreamEvent> = flow {
        // A2b: tool-result turns also carry MessageRole.USER (see
        // ChatTurn) but are not user messages — attachments belong to
        // real user turns (mirrors the OpenAI client). Gemini matches
        // a functionResponse to its call by NAME, so result turns
        // resolve the name from the conversation's own tool calls.
        val lastUserIndex = request.turns.indexOfLast { it.role == MessageRole.USER && it.toolCallId == null }
        val nameByCallId: Map<String, String> =
            request.turns.flatMap { it.toolCalls }.associate { it.id to it.name }
        val contents = buildJsonArray {
            request.turns.forEachIndexed { index, turn ->
                val isLastUser = turn.role == MessageRole.USER && index == lastUserIndex
                add(buildJsonObject {
                    put("role", if (turn.role == MessageRole.USER) "user" else "model")
                    put("parts", buildJsonArray {
                        // A2b (§5.2): native tool-calling turn shapes.
                        // Plain turns render exactly as before
                        // (byte-identical no-tools requests are a
                        // regression lock).
                        when {
                            turn.toolCallId != null -> add(buildJsonObject {
                                put("functionResponse", buildJsonObject {
                                    put("name", nameByCallId[turn.toolCallId] ?: turn.toolCallId)
                                    put("response", buildJsonObject { put("result", turn.content) })
                                })
                            })
                            turn.toolCalls.isNotEmpty() -> {
                                if (turn.content.isNotBlank()) {
                                    add(buildJsonObject { put("text", turn.content) })
                                }
                                turn.toolCalls.forEach { call ->
                                    add(buildJsonObject {
                                        put("functionCall", buildJsonObject {
                                            put("name", call.name)
                                            put("args", jsonObjectOrEmpty(call.argumentsJson))
                                        })
                                    })
                                }
                            }
                            else -> {
                                add(buildJsonObject { put("text", turn.content) })
                                if (isLastUser) request.attachments.forEach { a ->
                                    when {
                                        a.isImage && a.base64 != null -> add(buildJsonObject {
                                            put("inlineData", buildJsonObject { put("mimeType", a.mimeType); put("data", a.base64) })
                                        })
                                        !a.text.isNullOrBlank() -> add(buildJsonObject { put("text", "\n[Attachment: ${a.name}]\n${a.text}") })
                                    }
                                }
                            }
                        }
                    })
                })
            }
        }
        val body = buildJsonObject {
            put(
                "systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", request.systemPrompt) })
                    })
                }
            )
            put("contents", contents)
            // A2b (§5.2): client-executed tools ride as
            // functionDeclarations in the same tools array as the
            // google_search grounding entry. The array is emitted
            // only when at least one of them is present, and a
            // search-only array renders exactly as before (the
            // no-tools body is a byte-identical regression lock).
            if (request.tools.isNotEmpty() || request.webSearch) {
                put("tools", buildJsonArray {
                    if (request.tools.isNotEmpty()) {
                        add(buildJsonObject {
                            put("functionDeclarations", buildJsonArray {
                                request.tools.forEach { tool ->
                                    add(buildJsonObject {
                                        put("name", tool.name)
                                        put("description", tool.description)
                                        put("parameters", jsonObjectOrEmpty(tool.parametersJsonSchema))
                                    })
                                }
                            })
                        })
                    }
                    if (request.webSearch) {
                        add(buildJsonObject { put("google_search", buildJsonObject { }) })
                    }
                })
            }
            put("generationConfig", buildJsonObject {
                (request.temperature ?: runtime.config.temperature)?.let { put("temperature", it) }
                (request.maxTokens ?: runtime.config.maxTokens)?.let { put("maxOutputTokens", it) }
                val modelId = runtime.config.model
                val supportsThinkingLevel = GeminiCapabilities.supportsThinkingLevel(modelId)
                val supportsThinkingBudget = GeminiCapabilities.supportsThinkingBudget(modelId)
                if (supportsThinkingLevel) {
                    val level = when (request.thinkingLevel) {
                        com.anvex.core.model.ThinkingLevel.LOW -> "low"
                        com.anvex.core.model.ThinkingLevel.NORMAL -> "medium"
                        com.anvex.core.model.ThinkingLevel.HIGH,
                        com.anvex.core.model.ThinkingLevel.XHIGH,
                        com.anvex.core.model.ThinkingLevel.MAX -> "high"
                    }
                    put("thinkingConfig", buildJsonObject { put("thinkingLevel", level) })
                } else if (supportsThinkingBudget) {
                    put("thinkingConfig", buildJsonObject { put("thinkingBudget", request.thinkingLevel.budgetTokens) })
                }
            })
        }
        val model = runtime.config.model
        val urlBuilder = base.toHttpUrl().newBuilder()
        if (!urlBuilder.build().pathSegments.any { it.equals("v1beta", ignoreCase = true) }) {
            urlBuilder.addPathSegment("v1beta")
        }
        val url = urlBuilder
            .addPathSegment("models")
            .addPathSegment("$model:streamGenerateContent")
            .addQueryParameter("alt", "sse")
            .build()
        val builder = Request.Builder()
            .url(url)
            .header("x-goog-api-key", runtime.apiKey)
            .header("Content-Type", "application/json")
        for ((k, v) in runtime.config.extraHeaders) builder.header(k, v)
        val req = builder
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var usage: Usage? = null
        var failure: AiError? = null
        var tokenCount = 0
        // A2b (§5.2): functionCall parts arrive WHOLE (no fragment
        // assembly) — collected in response order as (name, args).
        val pendingFunctionCalls = mutableListOf<Pair<String, kotlinx.serialization.json.JsonElement?>>()

        Sse.stream(http, req) { err -> failure = err }.collect { frame ->
            val obj = try { json.parseToJsonElement(frame.data).jsonObjectOrNull } catch (_: Exception) { null }
                ?: return@collect
            // Error frames: {"error": {"code":..., "message":...}}
            obj["error"]?.jsonObjectOrNull?.let { err ->
                val msg = err["message"]?.jsonPrimitiveOrNull?.contentOrNull ?: "Gemini stream error"
                failure = AiError(AiError.Kind.PROTOCOL, msg)
                return@collect
            }
            obj["promptFeedback"]?.jsonObjectOrNull?.get("blockReason")?.jsonPrimitiveOrNull?.contentOrNull?.let { reason ->
                failure = AiError(AiError.Kind.PROTOCOL, "Gemini blocked the prompt: $reason")
                return@collect
            }
            val candidate = obj["candidates"]?.jsonArrayOrNull?.firstOrNull()?.jsonObjectOrNull
            val finishReason = candidate?.get("finishReason")?.jsonPrimitiveOrNull?.contentOrNull
            if (finishReason.equals("SAFETY", ignoreCase = true) || finishReason.equals("BLOCKLIST", ignoreCase = true) || finishReason.equals("PROHIBITED_CONTENT", ignoreCase = true)) {
                failure = AiError(AiError.Kind.PROTOCOL, "Gemini blocked the response: ${finishReason}")
                return@collect
            }
            candidate
                ?.get("content")?.jsonObjectOrNull
                ?.get("parts")?.jsonArrayOrNull
                ?.forEach { part ->
                    val partObj = part.jsonObjectOrNull
                    // A2b (§5.2): a functionCall part is a complete
                    // call — collect it for the end-of-stream emit.
                    val functionCall = partObj?.get("functionCall")?.jsonObjectOrNull
                    if (functionCall != null) {
                        val name = functionCall["name"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
                        val args = functionCall["args"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }
                        pendingFunctionCalls += name to args
                    }
                    val text = partObj?.get("text")?.jsonPrimitiveOrNull?.contentOrNull
                    if (!text.isNullOrEmpty()) {
                        // v4.4 (architect feature 1): Gemini thought summaries stream with
                        // part.thought == true — surface them as reasoning, not answer text.
                        val isThought = partObj?.get("thought")?.jsonPrimitiveOrNull?.contentOrNull == "true"
                        if (isThought) {
                            emit(StreamEvent.Reasoning(text))
                        } else {
                            tokenCount++
                            emit(StreamEvent.Token(text))
                        }
                    }
                }
            candidate?.get("groundingMetadata")?.jsonObjectOrNull?.get("groundingChunks")?.jsonArrayOrNull?.let { chunks ->
                val sources = chunks.mapNotNull { chunk ->
                    val web = chunk.jsonObjectOrNull?.get("web")?.jsonObjectOrNull ?: return@mapNotNull null
                    val url = web["uri"]?.jsonPrimitiveOrNull?.contentOrNull ?: return@mapNotNull null
                    WebSource(web["title"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(), url)
                }
                if (sources.isNotEmpty()) emit(StreamEvent.Sources(sources))
            }
            obj["usageMetadata"]?.jsonObjectOrNull?.let { u ->
                usage = Usage(
                    promptTokens = u["promptTokenCount"].longOrNullCompat ?: 0,
                    completionTokens = u["candidatesTokenCount"].longOrNullCompat ?: 0,
                    totalTokens = u["totalTokenCount"].longOrNullCompat ?: 0,
                )
            }
        }
        // A2b (§5.2 + Q6): with functionCall parts collected, emit
        // them ONCE as ToolCallReady before Completed. Gemini calls
        // carry no id, so one is synthesized: the counter offset is
        // the number of tool calls already present in this request's
        // conversation. Within a session the conversation only
        // grows, so offsets are monotonic across turns and cannot
        // reset on resume — the failure mode of the note's
        // turn-index sketch. Ids are internal correlation only:
        // Gemini matches functionResponse parts by name. Args that
        // are not a JSON object fail with PROTOCOL — a malformed
        // call is never silently emitted.
        var toolCallsEmitted = false
        if (failure == null && pendingFunctionCalls.isNotEmpty()) {
            val malformed = pendingFunctionCalls.firstOrNull { (name, args) ->
                name.isBlank() ||
                    (args != null && args !is kotlinx.serialization.json.JsonObject)
            }
            if (malformed != null) {
                failure = AiError(
                    AiError.Kind.PROTOCOL,
                    "Tool call '${malformed.first}' returned malformed arguments JSON",
                )
            } else {
                val offset = request.turns.sumOf { it.toolCalls.size }
                val assembled = pendingFunctionCalls.mapIndexed { index, (name, args) ->
                    AiToolCall(
                        id = "gemini-${offset + index}",
                        name = name,
                        argumentsJson = args?.toString() ?: "{}",
                    )
                }
                emit(StreamEvent.ToolCallReady(assembled))
                toolCallsEmitted = true
            }
        }
        val err = failure
        when {
            err != null -> emit(StreamEvent.Failed(err))
            tokenCount > 0 || toolCallsEmitted -> emit(StreamEvent.Completed("STOP", usage))
            else -> emit(StreamEvent.Failed(AiError(AiError.Kind.PROTOCOL, "Stream ended without content")))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(): List<ModelInfo> {
        val modelsUrlBuilder = base.toHttpUrl().newBuilder()
        if (!modelsUrlBuilder.build().pathSegments.any { it.equals("v1beta", ignoreCase = true) }) {
            modelsUrlBuilder.addPathSegment("v1beta")
        }
        val modelsUrl = modelsUrlBuilder.addPathSegment("models").addQueryParameter("pageSize", "1000").build()
        val req = Request.Builder()
            .url(modelsUrl)
            .header("x-goog-api-key", runtime.apiKey)
            .get()
            .build()
        return makeCall(http, req) { text ->
            val root = json.parseToJsonElement(text).jsonObjectOrNull
            val models = root?.get("models")?.jsonArrayOrNull ?: return@makeCall emptyList()
            models.mapNotNull { el ->
                val o = el.jsonObjectOrNull ?: return@mapNotNull null
                val name = o["name"]?.jsonPrimitiveOrNull?.contentOrNull ?: return@mapNotNull null
                val id = name.removePrefix("models/")
                ModelInfo(
                    id = id,
                    displayName = o["displayName"]?.jsonPrimitiveOrNull?.contentOrNull ?: id,
                )
            }.filter { !it.id.contains("embedding") && !it.id.contains("aqa") }
        }
    }

    /** A JSON string as a JSON object (empty object when unparseable or not an object). */
    private fun jsonObjectOrEmpty(text: String): kotlinx.serialization.json.JsonObject =
        try {
            json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject ?: buildJsonObject {}
        } catch (_: Exception) {
            buildJsonObject {}
        }

    override suspend fun validateConnection(): ValidationResult {
        val t0 = System.currentTimeMillis()
        return try {
            val models = listModels()
            ValidationResult(true, System.currentTimeMillis() - t0, "Connected. ${models.size} models available.", models.size)
        } catch (e: AiHttpException) {
            ValidationResult(false, System.currentTimeMillis() - t0, Sse.mapHttpError(e.code, e.body ?: "").message)
        } catch (e: Exception) {
            ValidationResult(false, System.currentTimeMillis() - t0, "Network error: ${e.message ?: "unreachable"}")
        }
    }
}
