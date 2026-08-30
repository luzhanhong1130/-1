package com.llmhub.app.data.db.dao

import androidx.room.*
import com.llmhub.app.data.model.ApiKeyConfig
import com.llmhub.app.data.model.ModelProvider
import kotlinx.coroutines.flow.Flow

@Dao
interface ApiKeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(key: ApiKeyConfig): Long
    @Update
    suspend fun update(key: ApiKeyConfig)
    @Delete
    suspend fun delete(key: ApiKeyConfig)
    @Query("DELETE FROM api_keys WHERE id = :id")
    suspend fun deleteById(id: Long)
    @Query("SELECT * FROM api_keys ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ApiKeyConfig>>
    @Query("SELECT * FROM api_keys WHERE id = :id")
    suspend fun get(id: Long): ApiKeyConfig?
    @Query("SELECT * FROM api_keys WHERE provider = :provider ORDER BY createdAt DESC")
    suspend fun getByProvider(provider: ModelProvider): List<ApiKeyConfig>
}

// 软件签名：TRAE AI 开发环境
// 大模型签名：Trae 智能助手
