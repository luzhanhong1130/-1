package com.llmhub.app.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.llmhub.app.data.db.dao.ChatDao
import com.llmhub.app.data.db.dao.ModelConfigDao
import com.llmhub.app.data.db.dao.UsageDao
import com.llmhub.app.data.model.ChatMessage
import com.llmhub.app.data.model.ChatRole
import com.llmhub.app.data.model.ChatSession
import com.llmhub.app.data.model.ModelConfig
import com.llmhub.app.data.model.UsageRecord
import com.llmhub.app.data.prefs.SecureKeyStore
import com.llmhub.app.data.remote.ChatRequest
import com.llmhub.app.data.remote.ChatStreamEvent
import com.llmhub.app.data.remote.LlmProviderFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * 聊天核心仓库。
 *
 * - 管理 session / message 持久化
 * - 拼装 [ChatRequest]、调用 [LlmProviderFactory] 流式生成 assistant 回复
 * - 把每帧 Delta 落库（驱动 Room Flow → UI 自动刷新）
 * - 请求结束后写入 [UsageRecord]，统计页由此驱动
 */
@Singleton
class ChatRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatDao: ChatDao,
    private val modelConfigDao: ModelConfigDao,
    private val usageDao: UsageDao,
    private val providerFactory: LlmProviderFactory,
    private val secureKeyStore: SecureKeyStore,
) {
    fun observeSessions(): Flow<List<ChatSession>> = chatDao.observeSessions()

    fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
        chatDao.observeMessages(sessionId)

    suspend fun getSession(sessionId: String): ChatSession? = chatDao.getSession(sessionId)

    suspend fun getModelConfig(id: Long): ModelConfig? = modelConfigDao.get(id)

    suspend fun getDefaultModelConfig(): ModelConfig? = modelConfigDao.getDefault()

    suspend fun createSession(firstModelId: Long? = null): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        chatDao.insertSession(ChatSession(id, DEFAULT_TITLE, now, now, firstModelId))
        return id
    }

    suspend fun renameSession(sessionId: String, title: String) {
        val s = chatDao.getSession(sessionId) ?: return
        chatDao.updateSession(s.copy(title = title, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteSession(sessionId: String) {
        chatDao.deleteMessagesOf(sessionId)
        chatDao.deleteSessionById(sessionId)
        usageDao.deleteBySession(sessionId)
    }

    /**
     * 发送一条用户消息 + 流式生成回复。
     *
     * 返回的 Flow 把 [ChatStreamEvent] 透传给上层 UI 用于渲染打字动画；
     * 同时所有状态都同步落库，UI 通过 [observeMessages] 也能拿到最终结果。
     */
    fun sendUserMessageAndStream(
        sessionId: String,
        modelConfig: ModelConfig,
        text: String,
    ): Flow<ChatStreamEvent> = channelFlow {
        // 网络预检：无网直接报错，避免傻等 connectTimeout 30s（参见 P1-6）
        if (!isOnline()) {
            send(ChatStreamEvent.Error("网络异常，请检查连接"))
            return@channelFlow
        }
        val now = System.currentTimeMillis()
        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = ChatRole.USER,
            content = text,
            timestamp = now,
        )
        chatDao.insertMessage(userMsg)

        val assistantId = UUID.randomUUID().toString()
        val assistantBase = ChatMessage(
            id = assistantId,
            sessionId = sessionId,
            role = ChatRole.ASSISTANT,
            content = "",
            timestamp = System.currentTimeMillis(),
            modelConfigId = modelConfig.id,
            isStreaming = true,
        )
        chatDao.insertMessage(assistantBase)

        // 首条用户消息把会话标题更新为内容前缀
        chatDao.getSession(sessionId)?.let { s ->
            if (s.title == DEFAULT_TITLE) {
                val title = text.lineSequence().firstOrNull()?.take(24)?.ifBlank { DEFAULT_TITLE }
                    ?: DEFAULT_TITLE
                chatDao.updateSession(
                    s.copy(
                        title = title,
                        updatedAt = System.currentTimeMillis(),
                        lastModelConfigId = modelConfig.id,
                    ),
                )
            } else {
                chatDao.updateSession(s.copy(updatedAt = System.currentTimeMillis(), lastModelConfigId = modelConfig.id))
            }
        }

        val accumulated = StringBuilder()
        var inputTokens = 0
        var outputTokens = 0
        val startTime = System.currentTimeMillis()
        // 流式期间节流 DB 写：每帧 updateMessage 会触发 Room Flow 重发，
        // 高频 token 输出下 DB 写入压力过大；只在距上次写入超过 FLUSH_INTERVAL_MS
        // 或终态（Done/Error/catch）时落盘。channelFlow 的 send 不受影响，UI 打字动画照常。
        var lastFlushTime = 0L
        suspend fun flushStreamingContent() {
            chatDao.updateMessage(assistantBase.copy(content = accumulated.toString()))
            lastFlushTime = System.currentTimeMillis()
        }

        // 统一收尾：把本次请求落库成 UsageRecord，避免异常路径漏记导致统计对账缺失。
        // 用 usageRecorded 标记保证 Done / Error / catch 三条路径只插入一次。
        var usageRecorded = false
        suspend fun recordUsage(success: Boolean) {
            if (usageRecorded) return
            usageRecorded = true
            // 协议未返回 usage 时按字符粗估（中文≈1.5 字/token）
            val estOutput = if (outputTokens > 0) outputTokens else (accumulated.length * 2 / 3).coerceAtLeast(1)
            val cost = modelConfig.priceInputPer1k * inputTokens / 1000.0 +
                modelConfig.priceOutputPer1k * estOutput / 1000.0
            usageDao.insert(
                UsageRecord(
                    sessionId = sessionId,
                    modelConfigId = modelConfig.id,
                    provider = modelConfig.provider,
                    timestamp = System.currentTimeMillis(),
                    inputTokens = inputTokens,
                    outputTokens = estOutput,
                    estimatedCostYuan = cost,
                    success = success,
                    latencyMs = System.currentTimeMillis() - startTime,
                ),
            )
        }

        try {
            val apiKey = secureKeyStore.loadKey(modelConfig.apiKeyRefId)
                ?: error("该模型未关联 API Key，请先在「密钥」中添加")

            // 上下文：过滤掉历史失败消息
            val history = chatDao.getMessages(sessionId).filterNot { it.error != null }
            val request = ChatRequest(
                modelConfig = modelConfig,
                apiKey = apiKey,
                messages = history,
            )
            val provider = providerFactory.forProtocol(modelConfig.provider.protocolKind)

            provider.streamChat(request).collect { ev ->
                when (ev) {
                    is ChatStreamEvent.Delta -> {
                        accumulated.append(ev.text)
                        if (System.currentTimeMillis() - lastFlushTime > FLUSH_INTERVAL_MS) {
                            flushStreamingContent()
                        }
                        send(ev)
                    }
                    is ChatStreamEvent.Usage -> {
                        if (ev.inputTokens > 0) inputTokens = ev.inputTokens
                        if (ev.outputTokens > 0) outputTokens = ev.outputTokens
                        send(ev)
                    }
                    is ChatStreamEvent.Error -> {
                        chatDao.updateMessage(
                            assistantBase.copy(
                                content = accumulated.toString(),
                                error = ev.message,
                                isStreaming = false,
                            ),
                        )
                        // 协议层错误（鉴权失败/限流/模型不存在）也消耗了 token，必须落库 success=false
                        recordUsage(success = false)
                        send(ev)
                    }
                    ChatStreamEvent.Done -> {
                        // 协议未返回 usage 时按字符粗估（中文≈1.5 字/token）
                        val estOutput = if (outputTokens > 0) outputTokens else (accumulated.length * 2 / 3).coerceAtLeast(1)
                        chatDao.updateMessage(
                            assistantBase.copy(
                                content = accumulated.toString(),
                                isStreaming = false,
                                inputTokens = inputTokens,
                                outputTokens = estOutput,
                            ),
                        )
                        recordUsage(success = true)
                        send(ChatStreamEvent.Done)
                    }
                }
            }
        } catch (e: Exception) {
            chatDao.updateMessage(
                assistantBase.copy(
                    content = accumulated.toString(),
                    error = e.message ?: "未知错误",
                    isStreaming = false,
                ),
            )
            // 未捕获异常（如 secureKeyStore 抛 error）也补记一次失败请求
            recordUsage(success = false)
            send(ChatStreamEvent.Error(e.message ?: "未知错误"))
        }
    }.buffer(Channel.UNLIMITED) // 缓解下游 UI 慢消费丢帧（参见 P1-10）

    /** 网络可用性预检（基于 ACCESS_NETWORK_STATE）。 */
    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val DEFAULT_TITLE = "新对话"
        /** 流式 DB 写节流间隔，避免每帧 chunk 都触发 Room Flow 重发。 */
        const val FLUSH_INTERVAL_MS = 100L
    }
}
