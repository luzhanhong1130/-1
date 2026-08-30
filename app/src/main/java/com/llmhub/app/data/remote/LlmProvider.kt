package com.llmhub.app.data.remote

import com.llmhub.app.data.model.ChatMessage
import com.llmhub.app.data.model.ModelConfig
import kotlinx.coroutines.flow.Flow

/** 一次对话请求。API Key 由调用方从 [com.llmhub.app.data.prefs.SecureKeyStore] 解密后注入。 */
data class ChatRequest(
    val modelConfig: ModelConfig,
    val apiKey: String,
    val messages: List<ChatMessage>,
)

/** Provider 对外吐出的流式事件。 */
sealed interface ChatStreamEvent {

    /** 增量文本片段，UI 拼接渲染。 */
    data class Delta(val text: String) : ChatStreamEvent

    /** Token 用量（部分协议每帧都带，部分只结束带）。最后一次为准。 */
    data class Usage(val inputTokens: Int, val outputTokens: Int) : ChatStreamEvent

    /** 协议层错误（鉴权失败、限流、模型不存在等）。 */
    data class Error(val message: String, val httpCode: Int? = null) : ChatStreamEvent

    /** 流正常结束。 */
    data object Done : ChatStreamEvent
}

/**
 * 大模型协议抽象。所有服务商统一走此接口，
 * 上层只需区分 [com.llmhub.app.data.model.ProtocolKind] 选择具体实现即可。
 */
interface LlmProvider {
    fun streamChat(request: ChatRequest): Flow<ChatStreamEvent>
}
