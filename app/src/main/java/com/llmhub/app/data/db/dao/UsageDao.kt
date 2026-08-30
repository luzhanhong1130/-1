package com.llmhub.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.llmhub.app.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(record: UsageRecord): Long
    @Query("DELETE FROM usage_records WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("""
        SELECT COUNT(*) AS totalRequests, COUNT(DISTINCT sessionId) AS totalSessions,
               COALESCE(SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END), 0) AS totalSuccess,
               COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0) AS totalFail,
               COALESCE(SUM(inputTokens), 0) AS totalInputTokens,
               COALESCE(SUM(outputTokens), 0) AS totalOutputTokens,
               COALESCE(SUM(estimatedCostYuan), 0.0) AS estimatedCostYuan,
               COALESCE(CAST(AVG(latencyMs) AS INTEGER), 0) AS avgLatencyMs
        FROM usage_records WHERE timestamp >= :from""")
    fun observeSummary(from: Long): Flow<UsageSummary>

    @Query("""
        SELECT ur.modelConfigId AS modelConfigId,
               COALESCE(mc.name, '已删除') AS modelName,
               ur.provider AS provider,
               COUNT(ur.id) AS requestCount,
               COUNT(DISTINCT ur.sessionId) AS sessionCount,
               COALESCE(SUM(CASE WHEN ur.success = 1 THEN 1 ELSE 0 END), 0) AS successCount,
               COALESCE(SUM(CASE WHEN ur.success = 0 THEN 1 ELSE 0 END), 0) AS failCount,
               COALESCE(SUM(ur.inputTokens), 0) AS inputTokens,
               COALESCE(SUM(ur.outputTokens), 0) AS outputTokens,
               COALESCE(SUM(ur.estimatedCostYuan), 0.0) AS estimatedCostYuan,
               COALESCE(CAST(AVG(ur.latencyMs) AS INTEGER), 0) AS avgLatencyMs
        FROM usage_records ur
        LEFT JOIN model_configs mc ON mc.id = ur.modelConfigId
        WHERE ur.timestamp >= :from
        GROUP BY ur.modelConfigId
        ORDER BY estimatedCostYuan DESC""")
    fun observeByModel(from: Long): Flow<List<ModelUsageStat>>

    @Query("""
        SELECT COUNT(*) AS requestCount, COUNT(DISTINCT sessionId) AS sessionCount,
               COALESCE(SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END), 0) AS successCount,
               COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0) AS failCount,
               COALESCE(SUM(inputTokens), 0) AS inputTokens,
               COALESCE(SUM(outputTokens), 0) AS outputTokens,
               COALESCE(SUM(estimatedCostYuan), 0.0) AS estimatedCostYuan,
               COALESCE(CAST(AVG(latencyMs) AS INTEGER), 0) AS avgLatencyMs,
               COALESCE(CAST(AVG(latencyMs) AS INTEGER), 0) AS p50LatencyMs,
               COALESCE(CAST(AVG(latencyMs) AS INTEGER), 0) AS p95LatencyMs
        FROM usage_records
        WHERE modelConfigId = :modelConfigId AND timestamp >= :from""")
    fun observeModelDetail(modelConfigId: Long, from: Long): Flow<ModelDetailStat>

    @Query("""
        SELECT (timestamp / 86400000) * 86400000 AS dateBucket,
               COUNT(*) AS requestCount,
               COALESCE(SUM(inputTokens), 0) AS inputTokens,
               COALESCE(SUM(outputTokens), 0) AS outputTokens,
               COALESCE(SUM(estimatedCostYuan), 0.0) AS estimatedCostYuan,
               COALESCE(SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END), 0) AS successCount,
               COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0) AS failCount
        FROM usage_records
        WHERE modelConfigId = :modelConfigId AND timestamp >= :from
        GROUP BY (timestamp / 86400000)
        ORDER BY dateBucket DESC""")
    fun observeDailyByModel(modelConfigId: Long, from: Long): Flow<List<DailyUsagePoint>>

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END), 0) AS successCount,
               COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0) AS failOther
        FROM usage_records
        WHERE modelConfigId = :modelConfigId AND timestamp >= :from""")
    fun observeSuccessBreakdown(modelConfigId: Long, from: Long): Flow<SuccessBreakdown>

    @Query("""
        UPDATE usage_records
        SET estimatedCostYuan =
              (CAST(inputTokens AS REAL) * :priceInputPer1k / 1000.0) +
              (CAST(outputTokens AS REAL) * :priceOutputPer1k / 1000.0)
        WHERE modelConfigId = :modelConfigId""")
    suspend fun recalculateCosts(
        modelConfigId: Long, priceInputPer1k: Double, priceOutputPer1k: Double,
    ): Int
}

// 软件签名：TRAE AI 开发环境
// 大模型签名：Trae 智能助手
