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
 * Anthropic (Claude) 协议实现。
 *
 * - 端点：`${baseUrl}/v1/messages`
 * - 鉴权：`x-api-key` + `anthropic-version: 2023-06-01`
 * - system 消息从 messages 中抽出，放到顶层 `system` 字段（Claude 协议要求）
 * - SSE 事件：`message_start` / `content_block_delta` / `message_delta` / `message_stop`
 */
class AnthropicProvider @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) : LlmProvider {

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = callbackFlow {
        val req = buildRequest(request)
        val factory = EventSources.createFactory(client)
        // Done 只允许发一次：message_stop 帧与 onClosed 关闭都可能触发，
        // 双发会导致 ChatRepository 对同一条 assistant 消息重复落库收尾。
        val doneSent = java.util.concurrent.atomic.AtomicBoolean(false)

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                parseEvent(type, data)?.let { trySend(it) }
                if (type == "message_stop" && doneSent.compareAndSet(false, true)) {
                    trySend(ChatStreamEvent.Done)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (doneSent.compareAndSet(false, true)) trySend(ChatStreamEvent.Done)
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
            put("model", request.modelConfig.modelId)
            put("max_tokens", request.modelConfig.maxTokens)
            put("temperature", request.modelConfig.temperature.toDouble())
            put("stream", true)
            if (systemText.isNotEmpty()) put("system", systemText)
            put(
                "messages",
                buildJsonArray {
                    dialogue.forEach { m ->
                        add(
                            buildJsonObject {
                                put("role", if (m.role == ChatRole.ASSISTANT) "assistant" else "user")
                                put("content", m.content)
                            },
                        )
                    }
                },
            )
        }
        return Request.Builder()
            .url(resolveEndpoint(request.modelConfig.baseUrl))
            .addHeader("x-api-key", request.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MT))
            .build()
    }

    private fun parseEvent(type: String?, data: String): ChatStreamEvent? = runCatching {
        if (data.isEmpty()) return@runCatching null
        val obj = json.parseToJsonElement(data).let { runCatching { it as JsonObject }.getOrNull() }
        when (type) {
            "message_start" -> {
                val usage = obj?.objectOrNull("message")?.objectOrNull("usage")
                ChatStreamEvent.Usage(usage?.intOrNull("input_tokens") ?: 0, 0)
            }
            "content_block_delta" -> {
                val delta = obj?.objectOrNull("delta")
                delta?.stringOrNull("text")?.let { ChatStreamEvent.Delta(it) }
            }
            "message_delta" -> {
                val usage = obj?.objectOrNull("usage")
                val out = usage?.intOrNull("output_tokens") ?: 0
                ChatStreamEvent.Usage(0, out)
            }
            else -> null
        }
    }.onFailure { Log.w(TAG, "parseEvent failed: ${it.message}", it) }.getOrNull()

    private fun resolveEndpoint(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/v1/messages")) base else "$base/v1/messages"
    }

    private fun failureMessage(t: Throwable?, response: Response?): String =
        response?.let { "HTTP ${it.code}" } ?: t?.message ?: "网络异常"

    private companion object {
        const val TAG = "AnthropicProvider"
        val JSON_MT = "application/json; charset=utf-8".toMediaType()
    }
}
