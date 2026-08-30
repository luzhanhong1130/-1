package com.llmhub.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户配置的单个模型（一个服务商可配多个不同 modelId / 价格的条目）。
 *
 * - baseUrl: 模型调用的接口前缀，默认从 [ModelProvider.defaultBaseUrl] 填充，可改。
 * - apiKeyRefId: 指向 [ApiKeyConfig.id]，真正的 Key 值加密存于 EncryptedSharedPreferences。
 * - 价格字段用于「消耗统计」页估算费用（按 1K tokens 计价，单位：元）。
 * - billingEndpointKind: 后台用量查询走哪种 API 协议（默认 DISABLED，由 [ModelProvider.defaultBillingKind]
 *   根据 provider 推导默认值，用户可在模型编辑页手动调整）。
 */
@Entity(tableName = "model_configs")
data class ModelConfig(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val provider: ModelProvider,
    val modelId: String,
    val baseUrl: String,
    val apiKeyRefId: Long,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val priceInputPer1k: Double = 0.0,
    val priceOutputPer1k: Double = 0.0,
    val isDefault: Boolean = false,
    val billingEndpointKind: PlatformBillingKind = PlatformBillingKind.DISABLED,
)

/**
 * API Key 配置条目。
 *
 * 安全设计：Key 明文绝不进数据库，仅以 `apikey_${id}` 为键加密存于
 * EncryptedSharedPreferences（见 [com.llmhub.app.data.prefs.SecureKeyStore]）。
 */
@Entity(tableName = "api_keys")
data class ApiKeyConfig(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val provider: ModelProvider,
    val createdAt: Long = System.currentTimeMillis(),
)

// 软件签名：TRAE AI 开发环境
// 大模型签名：Trae 智能助手
