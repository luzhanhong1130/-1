package com.llmhub.app.data.repository

import com.llmhub.app.data.db.dao.ChatDao
import com.llmhub.app.data.db.dao.ModelConfigDao
import com.llmhub.app.data.model.ModelConfig
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelConfigRepository @Inject constructor(
    private val dao: ModelConfigDao,
    private val chatDao: ChatDao,
) {
    fun observeAll(): Flow<List<ModelConfig>> = dao.observeAll()

    fun observeById(id: Long): Flow<ModelConfig?> = dao.observeById(id)

    suspend fun getAll(): List<ModelConfig> = dao.getAll()

    suspend fun get(id: Long): ModelConfig? = dao.get(id)

    suspend fun getDefault(): ModelConfig? = dao.getDefault()

    suspend fun setDefault(id: Long) = dao.setDefault(id)

    /** 新增或更新：id==0 视为新增。 */
    suspend fun save(config: ModelConfig): Long {
        return if (config.id == 0L) {
            val newId = dao.insert(config)
            // 若库中尚无默认模型，把首个新增项设为默认
            if (dao.getDefault() == null) dao.setDefault(newId)
            newId
        } else {
            dao.update(config)
            config.id
        }
    }

    /**
     * 删除前级联清理外键引用：
     * - chat_messages.modelConfigId / chat_sessions.lastModelConfigId 置 NULL
     * - usage_records.modelConfigId 保留（统计页 LEFT JOIN 已处理"已删除"显示）
     * 否则删除后历史消息气泡无模型、统计页触发 NPE、切换会话恢复模型返回 null。
     */
    suspend fun delete(config: ModelConfig) {
        chatDao.clearModelConfigRefsInMessages(config.id)
        chatDao.clearModelConfigRefsInSessions(config.id)
        dao.delete(config)
        // 若删的是默认模型，挑剩余的第一个补为默认，避免默认落空
        if (config.isDefault) {
            dao.getAll().firstOrNull()?.let { dao.setDefault(it.id) }
        }
    }

    /** 启动时若无默认模型，挑第一个补上。 */
    suspend fun ensureDefault(): ModelConfig? = dao.getDefault() ?: dao.getAll().firstOrNull()?.also {
        dao.setDefault(it.id)
    }
}
