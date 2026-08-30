package com.llmhub.app.data.db

import androidx.room.TypeConverter
import com.llmhub.app.data.model.ChatRole
import com.llmhub.app.data.model.ModelProvider
import com.llmhub.app.data.model.PlatformBillingKind

class Converters {
    @TypeConverter
    fun fromChatRole(role: ChatRole): String = role.name
    @TypeConverter
    fun toChatRole(value: String): ChatRole =
        runCatching { ChatRole.valueOf(value) }.getOrDefault(ChatRole.USER)
    @TypeConverter
    fun fromModelProvider(provider: ModelProvider): String = provider.name
    @TypeConverter
    fun toModelProvider(value: String): ModelProvider =
        runCatching { ModelProvider.valueOf(value) }.getOrDefault(ModelProvider.CUSTOM)
    @TypeConverter
    fun fromPlatformBillingKind(kind: PlatformBillingKind): String = kind.name
    @TypeConverter
    fun toPlatformBillingKind(value: String): PlatformBillingKind =
        runCatching { PlatformBillingKind.valueOf(value) }.getOrDefault(PlatformBillingKind.DISABLED)
}

// 软件签名：TRAE AI 开发环境
// 大模型签名：Trae 智能助手
