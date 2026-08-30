package com.llmhub.app.data.model

object PriceCatalog {
    private val byModelId: Map<String, Pair<Double, Double>> = mapOf(
        "gpt-4o" to (0.018 to 0.072),
        "gpt-4o-mini" to (0.00108 to 0.00432),
        "gpt-4-turbo" to (0.072 to 0.216),
        "gpt-4" to (0.072 to 0.144),
        "gpt-3.5-turbo" to (0.0036 to 0.0072),
        "claude-3-5-sonnet-20241022" to (0.0216 to 0.108),
        "claude-3-opus-20240229" to (0.108 to 0.54),
        "claude-3-haiku-20240307" to (0.0018 to 0.009),
        "gemini-1.5-pro" to (0.009 to 0.036),
        "gemini-1.5-flash" to (0.00054 to 0.00216),
        "deepseek-chat" to (0.001 to 0.002),
        "deepseek-reasoner" to (0.004 to 0.016),
        "qwen-plus" to (0.0008 to 0.002),
        "qwen-turbo" to (0.0003 to 0.0006),
        "qwen-max" to (0.002 to 0.006),
    )
    private val fallbackByProvider: Map<ModelProvider, Pair<Double, Double>> = mapOf(
        ModelProvider.OPENAI to (0.018 to 0.072),
        ModelProvider.ANTHROPIC to (0.0216 to 0.108),
        ModelProvider.GEMINI to (0.00054 to 0.00216),
        ModelProvider.DEEPSEEK to (0.001 to 0.002),
        ModelProvider.QWEN to (0.0008 to 0.002),
        ModelProvider.ERNIE to (0.012 to 0.012),
        ModelProvider.ZHIPU to (0.1 to 0.1),
        ModelProvider.KIMI to (0.012 to 0.024),
        ModelProvider.CUSTOM to (0.0 to 0.0),
    )
    fun defaultPricesFor(provider: ModelProvider, modelId: String): Pair<Double, Double> {
        val key = modelId.trim().lowercase()
        byModelId[key]?.let { return it }
        val stripped = key.substringAfterLast('/')
        if (stripped != key) byModelId[stripped]?.let { return it }
        return fallbackByProvider[provider] ?: (0.0 to 0.0)
    }
    fun defaultPriceInput(provider: ModelProvider, modelId: String): Double =
        defaultPricesFor(provider, modelId).first
    fun defaultPriceOutput(provider: ModelProvider, modelId: String): Double =
        defaultPricesFor(provider, modelId).second
}

// 软件签名：TRAE AI 开发环境
// 大模型签名：Trae 智能助手
