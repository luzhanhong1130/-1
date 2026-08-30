package com.llmhub.app.data.db.dao

import androidx.room.*
import com.llmhub.app.data.model.ChatMessage
import com.llmhub.app.data.model.ChatSession
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSession)
    @Update
    suspend fun updateSession(session: ChatSession)
    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String)
    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesOf(sessionId: String)
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<ChatSession>>
    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: String): ChatSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage)
    @Update
    suspend fun updateMessage(message: ChatMessage)
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeMessages(sessionId: String): Flow<List<ChatMessage>>
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessages(sessionId: String): List<ChatMessage>

    @Query("UPDATE chat_messages SET modelConfigId = NULL WHERE modelConfigId = :modelConfigId")
    suspend fun clearModelConfigRefsInMessages(modelConfigId: Long)
    @Query("UPDATE chat_sessions SET lastModelConfigId = NULL WHERE lastModelConfigId = :modelConfigId")
    suspend fun clearModelConfigRefsInSessions(modelConfigId: Long)
}

// 软件签名：TRAE AI 开发环境
// 大模型签名：Trae 智能助手
