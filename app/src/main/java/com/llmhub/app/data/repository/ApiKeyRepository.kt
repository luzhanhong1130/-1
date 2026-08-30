package com.llmhub.app.data.repository

import com.llmhub.app.data.db.dao.ApiKeyDao
import com.llmhub.app.data.db.dao.ModelConfigDao
import com.llmhub.app.data.model.ApiKeyConfig
import com.llmhub.app.data.model.ModelProvider
import com.llmhub.app.data.prefs.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiKeyRepository @Inject constructor(
    private val apiKeyDao: ApiKeyDao,
    private val modelConfigDao: ModelConfigDao,
    private val secureKeyStore: SecureKeyStore,
) {
    fun observeAll(): Flow<List<ApiKeyConfig>> = apiKeyDao.observeAll()

    suspend fun get(id: Long): ApiKeyConfig? = apiKeyDao.get(id)

    suspend fun getByProvider(provider: ModelProvider): List<ApiKeyConfig> =
        apiKeyDao.getByProvider(provider)

    /**
     * 原子化新增：先写 Room 拿 id → 再加密落盘(commit 同步)。
     * 若加密落盘失败，回滚 Room 记录，避免留下「解密永远返回 null」的孤儿。
     */
    suspend fun add(name: String, provider: ModelProvider, value: String): Long {
        val id = apiKeyDao.insert(ApiKeyConfig(name = name, provider = provider))
        try {
            secureKeyStore.storeKey(id, value)
        } catch (t: Throwable) {
            apiKeyDao.deleteById(id)
            throw t
        }
        return id
    }

    suspend fun update(config: ApiKeyConfig, newValue: String?) {
        apiKeyDao.update(config)
        if (!newValue.isNullOrBlank()) secureKeyStore.storeKey(config.id, newValue)
    }

    /**
     * 删除前先级联解除 ModelConfig 中的 apiKeyRefId 引用（置 0），
     * 否则引用此 Key 的模型在发送消息时才会报「未关联 API Key」，错误提示与原因不一致。
     */
    suspend fun delete(config: ApiKeyConfig) {
        modelConfigDao.clearApiKeyRef(config.id)
        apiKeyDao.delete(config)
        secureKeyStore.deleteKey(config.id)
    }

    /** 解密读取某条 Key 的明文（IO 线程，避免主线程 ANR）。 */
    suspend fun loadDecrypted(id: Long): String? = withContext(Dispatchers.IO) {
        secureKeyStore.loadKey(id)
    }
}
