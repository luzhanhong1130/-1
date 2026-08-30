package com.llmhub.app.data.remote

import com.llmhub.app.data.model.ChatMessage
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * OpenAI 兼容协议实现。
 *
 * 覆盖：OpenAI / DeepSeek / Kimi / 通义 / 文心（千帆兼容模式）/ 智谱 / 自定义。
 *
 * 端点规则：
 *  - baseUrl 已含完整 `/chat/completions` 路径 → 直接用
 *  - baseUrl 以 `/vN` 结尾（如智谱 `/paas/v4`、千帆 `/v1`）→ 追加 `/chat/completions`
 *  - 否则追加 `/v1/chat/completions`
 *
 * 请求：`Authorization: Bearer ${apiKey}`，body 标准字段 + `stream_options.include_usage`。
 */
class OpenAICompatibleProvider @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) : LlmProvider {

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = callbackFlow {
        val req = buildRequest(request)
        val factory = EventSources.createFactory(client)
        // Done 只允许发一次：协议层 [DONE] 帧与 onClosed 关闭都可能触发，
        // 双发会导致 ChatRepository 对同一条 assistant 消息重复落库收尾。
        val doneSent = java.util.concurrent.atomic.AtomicBoolean(false)

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    if (doneSent.compareAndSet(false, true)) trySend(ChatStreamEvent.Done)
                    return
                }
                parseChunk(data)?.let { trySend(it) }
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
        val body = buildJsonObject {
            put("model", request.modelConfig.modelId)
            put("stream", true)
            put("stream_options", buildJsonObject { put("include_usage", true) })
            put(
                "messages",
                buildJsonArray {
                    request.messages.forEach { m ->
                        add(
                            buildJsonObject {
                                put("role", m.role.wire)
                                put("content", m.content)
                            }
                        )
                    }
                },
            )
            put("temperature", request.modelConfig.temperature.toDouble())
            put("max_tokens", request.modelConfig.maxTokens)
        }
        return Request.Builder()
            .url(resolveEndpoint(request.modelConfig.baseUrl))
            .addHeader("Authorization", "Bearer ${request.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MT))
            .build()
    }

    private fun parseChunk(data: String): ChatStreamEvent? = runCatching {
        val obj = json.parseToJsonElement(data).jsonObject
        val choices = obj["choices"]?.jsonArray
        val delta = choices?.firstOrNull()?.jsonObject?.get("delta")?.let {
            runCatching { it.jsonObject }.getOrNull()
        }
        val content = delta?.stringOrNull("content")
        if (content != null) return@runCatching ChatStreamEvent.Delta(content)

        // usage 帧：choices 可能为空数组，仅携带用量
        val usage = obj["usage"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val input = usage?.intOrNull("prompt_tokens") ?: 0
        val output = usage?.intOrNull("completion_tokens") ?: 0
        if (input == 0 && output == 0) null else ChatStreamEvent.Usage(input, output)
    }.onFailure { Log.w(TAG, "parseChunk failed: ${it.message}", it) }.getOrNull()

    private fun resolveEndpoint(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return when {
            base.endsWith("/chat/completions") -> base
            Regex("""/v\d+$""").find(base) != null -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    private fun failureMessage(t: Throwable?, response: Response?): String =
        response?.let { "HTTP ${it.code}" } ?: t?.message ?: "网络异常"

    private companion object {
        const val TAG = "OpenAIProvider"
        val JSON_MT = "application/json; charset=utf-8".toMediaType()
    }
}
