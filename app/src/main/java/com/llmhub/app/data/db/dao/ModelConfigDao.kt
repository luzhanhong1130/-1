package com.llmhub.app.data.db.dao

import androidx.room.*
import com.llmhub.app.data.model.ModelConfig
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: ModelConfig): Long
    @Update
    suspend fun update(config: ModelConfig)
    @Delete
    suspend fun delete(config: ModelConfig)
    @Query("SELECT * FROM model_configs ORDER BY id ASC")
    fun observeAll(): Flow<List<ModelConfig>>
    @Query("SELECT * FROM model_configs ORDER BY id ASC")
    suspend fun getAll(): List<ModelConfig>
    @Query("SELECT * FROM model_configs WHERE id = :id")
    suspend fun get(id: Long): ModelConfig?
    @Query("SELECT * FROM model_configs WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<ModelConfig?>
    @Query("SELECT * FROM model_configs WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): ModelConfig?
    @Query("UPDATE model_configs SET isDefault = (id = :id)")
    suspend fun setDefault(id: Long)
    @Query("UPDATE model_configs SET apiKeyRefId = 0 WHERE apiKeyRefId = :keyId")
    suspend fun clearApiKeyRef(keyId: Long)
}

// 软件签名：TRAE AI 开发环境
// 大模型签名：Trae 智能助手
