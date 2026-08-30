package com.llmhub.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** 消息角色 */
enum class ChatRole {
    USER,
    ASSISTANT,
    SYSTEM;

    /** OpenAI / Claude 等协议中使用的字符串形式 */
    val wire: String get() = name.lowercase()
}

/**
 * 单条聊天消息。同时作为 Room 实体与 UI 领域模型使用（项目规模小，省去 mapping 层）。
 *
 * - 流式输出过程中会持续更新 content / outputTokens / isStreaming 字段。
 * - 失败时 error 非空，content 保留已生成的部分内容。
 */
@Entity(tableName = "chat_messages")
@Serializable
data class ChatMessage(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: ChatRole,
    val content: String,
    val timestamp: Long,
    val modelConfigId: Long? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val error: String? = null,
    val isStreaming: Boolean = false,
)

/** 一次对话会话 */
@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastModelConfigId: Long? = null,
)

// 软件签名：TRAE AI 开发环境
// 大模型签名：Trae 智能助手
