package com.llmhub.app.data.remote

import com.llmhub.app.data.model.ProtocolKind
import com.llmhub.app.data.model.ModelProvider
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * 根据服务商的协议种类路由到对应的 LlmProvider 实现。
 *
 * 新增一个用 OpenAI 兼容协议的服务商时，无需改这里——
 * 只需在 [ModelProvider] 加一条目并把 protocolKind 设为 OPENAI_COMPATIBLE。
 */
@Singleton
class LlmProviderFactory @Inject constructor(
    private val openAiCompatibleProvider: Provider<OpenAICompatibleProvider>,
    private val anthropicProvider: Provider<AnthropicProvider>,
    private val geminiProvider: Provider<GeminiProvider>,
) {
    fun forProtocol(kind: ProtocolKind): LlmProvider = when (kind) {
        ProtocolKind.OPENAI_COMPATIBLE -> openAiCompatibleProvider.get()
        ProtocolKind.ANTHROPIC -> anthropicProvider.get()
        ProtocolKind.GEMINI -> geminiProvider.get()
    }
}
