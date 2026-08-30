package com.llmhub.app.data.remote

import com.llmhub.app.data.model.ChatRole
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import javax.inject.Inject

/**
 * Google Gemini 协议实现。
 *
 * - 端点：`${baseUrl}/v1beta/models/{model}:streamGenerateContent?alt=sse&key={apiKey}`
 *   `alt=sse` 让 Gemini 返回标准 SSE 格式，可复用 OkHttp 的 EventSource。
 * - 鉴权：API Key 通过 query 参数传递（也兼容 `x-goog-api-key` 头）。
 * - system 消息塞入顶层 `systemInstruction.parts[].text`。
 * - role 映射：ASSISTANT → "model"，其余 → "user"。
 */
class GeminiProvider @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) : LlmProvider {

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = callbackFlow {
        val req = buildRequest(request)
        val factory = EventSources.createFactory(client)

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                parseChunk(data)?.let { trySend(it) }
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(ChatStreamEvent.Done)
                channel.close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                trySend(ChatStreamEvent.Error(failureMessage(t, response), response?.code))
                channel.close(t)
            }
        }
        val es = factory.newEventSource(req, listener)
        awaitClose { es.cancel() }
    }

    private fun buildRequest(request: ChatRequest): Request {
        val systemText = request.messages
            .filter { it.role == ChatRole.SYSTEM }
            .joinToString("\n") { it.content }
        val dialogue = request.messages.filter { it.role != ChatRole.SYSTEM }

        val body = buildJsonObject {
            put(
                "contents",
                buildJsonArray {
                    dialogue.forEach { m ->
                        add(
                            buildJsonObject {
                                put("role", if (m.role == ChatRole.ASSISTANT) "model" else "user")
                                put(
                                    "parts",
                                    buildJsonArray {
                                        add(buildJsonObject { put("text", m.content) })
                                    },
                                )
                            },
                        )
                    }
                },
            )
            if (systemText.isNotEmpty()) {
                put(
                    "systemInstruction",
                    buildJsonObject {
                        put(
                            "parts",
                            buildJsonArray {
                                add(buildJsonObject { put("text", systemText) })
                            },
                        )
                    },
                )
            }
            put(
                "generationConfig",
                buildJsonObject {
                    put("temperature", request.modelConfig.temperature.toDouble())
                    put("maxOutputTokens", request.modelConfig.maxTokens)
                },
            )
        }
        return Request.Builder()
            .url(resolveEndpoint(request.modelConfig.baseUrl, request.modelConfig.modelId, request.apiKey))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MT))
            .build()
    }

    private fun parseChunk(data: String): ChatStreamEvent? = runCatching {
        if (data.isEmpty()) return@runCatching null
        val obj = json.parseToJsonElement(data).let {
            runCatching { it as JsonObject }.getOrNull()
        } ?: return@runCatching null

        // 取首个 candidate 的 text
        val candidate = obj.arrayOrNull("candidates")?.firstOrNull()
            ?.let { runCatching { it as JsonObject }.getOrNull() }
        val text = candidate?.objectOrNull("content")?.arrayOrNull("parts")?.firstOrNull()
            ?.let { runCatching { it as JsonObject }.getOrNull() }
            ?.stringOrNull("text")
        if (text != null) return@runCatching ChatStreamEvent.Delta(text)

        // 用量
        val usage = obj.objectOrNull("usageMetadata")
        val input = usage?.intOrNull("promptTokenCount") ?: 0
        val output = usage?.intOrNull("candidatesTokenCount") ?: 0
        if (input == 0 && output == 0) null else ChatStreamEvent.Usage(input, output)
    }.onFailure { Log.w(TAG, "parseChunk failed: ${it.message}", it) }.getOrNull()

    private fun resolveEndpoint(baseUrl: String, modelId: String, apiKey: String): String {
        val base = baseUrl.trimEnd('/')
        val path = if (base.endsWith("/v1beta")) base else "$base/v1beta"
        val url = "$path/models/$modelId:streamGenerateContent".toHttpUrl()
            .newBuilder()
            .addQueryParameter("alt", "sse")
            .addQueryParameter("key", apiKey)
            .build()
        return url.toString()
    }

    private fun failureMessage(t: Throwable?, response: Response?): String =
        response?.let { "HTTP ${it.code}" } ?: t?.message ?: "网络异常"

    private companion object {
        const val TAG = "GeminiProvider"
        val JSON_MT = "application/json; charset=utf-8".toMediaType()
    }
}
