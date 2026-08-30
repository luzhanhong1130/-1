package com.llmhub.app.data.model

enum class ModelProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val protocolKind: ProtocolKind,
) {
    OPENAI(displayName="OpenAI", defaultBaseUrl="https://api.openai.com", protocolKind=ProtocolKind.OPENAI_COMPATIBLE),
    ANTHROPIC(displayName="Claude (Anthropic)", defaultBaseUrl="https://api.anthropic.com", protocolKind=ProtocolKind.ANTHROPIC),
    GEMINI(displayName="Gemini (Google)", defaultBaseUrl="https://generativelanguage.googleapis.com", protocolKind=ProtocolKind.GEMINI),
    QWEN(displayName="通义千问", defaultBaseUrl="https://dashscope.aliyuncs.com/compatible-mode", protocolKind=ProtocolKind.OPENAI_COMPATIBLE),
    ERNIE(displayName="文心一言", defaultBaseUrl="https://qianfan.baidubce.com", protocolKind=ProtocolKind.OPENAI_COMPATIBLE),
    ZHIPU(displayName="智谱 GLM", defaultBaseUrl="https://open.bigmodel.cn/api/paas/v4", protocolKind=ProtocolKind.OPENAI_COMPATIBLE),
    DEEPSEEK(displayName="DeepSeek", defaultBaseUrl="https://api.deepseek.com", protocolKind=ProtocolKind.OPENAI_COMPATIBLE),
    KIMI(displayName="Kimi (Moonshot)", defaultBaseUrl="https://api.moonshot.cn", protocolKind=ProtocolKind.OPENAI_COMPATIBLE),
    CUSTOM(displayName="自定义", defaultBaseUrl="", protocolKind=ProtocolKind.OPENAI_COMPATIBLE);

    fun defaultBillingKind(): PlatformBillingKind = when (this) {
        OPENAI -> PlatformBillingKind.OPENAI_OFFICIAL
        DEEPSEEK -> PlatformBillingKind.DEEPSEEK
        QWEN -> PlatformBillingKind.DASHSCOPE
        else -> PlatformBillingKind.DISABLED
    }

    companion object {
        val selectable: List<ModelProvider> = listOf(
            OPENAI, ANTHROPIC, GEMINI, QWEN, ERNIE, ZHIPU, DEEPSEEK, KIMI, CUSTOM,
        )
    }
}

enum class ProtocolKind { OPENAI_COMPATIBLE, ANTHROPIC, GEMINI }

// 软件签名：TRAE AI 开发环境
// 大模型签名：Trae 智能助手
