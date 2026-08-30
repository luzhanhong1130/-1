package com.llmhub.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 单次请求的消耗记录，驱动「消耗统计」页。 */
@Entity(tableName = "usage_records")
data class UsageRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val modelConfigId: Long,
    val provider: ModelProvider,
    val timestamp: Long,
    val inputTokens: Int,
    val outputTokens: Int,
    val estimatedCostYuan: Double,
    val success: Boolean,
    val latencyMs: Long,
)

/**
 * 按模型聚合的统计结果（DAO 查询投影）。
 *
 * 显式标注 @ColumnInfo 以规避 Room 2.6.1 在 COALESCE 别名 / LEFT JOIN 场景下
 * 的 POJO 投影映射歧义。
 */
data class ModelUsageStat(
    @ColumnInfo(name = "modelConfigId") val modelConfigId: Long,
    @ColumnInfo(name = "modelName") val modelName: String,
    @ColumnInfo(name = "provider") val provider: ModelProvider,
    @ColumnInfo(name = "requestCount") val requestCount: Int,
    @ColumnInfo(name = "sessionCount") val sessionCount: Int,
    @ColumnInfo(name = "successCount") val successCount: Int,
    @ColumnInfo(name = "failCount") val failCount: Int,
    @ColumnInfo(name = "inputTokens") val inputTokens: Int,
    @ColumnInfo(name = "outputTokens") val outputTokens: Int,
    @ColumnInfo(name = "estimatedCostYuan") val estimatedCostYuan: Double,
    @ColumnInfo(name = "avgLatencyMs") val avgLatencyMs: Long,
) {
    /** 成功率，0.0 ~ 1.0；无数据时返回 -1f（UI 可自行处理为 N/A）。 */
    val successRate: Float
        get() = if (requestCount <= 0) -1f else successCount.toFloat() / requestCount
}

/** 汇总统计（DAO 查询投影）。 */
data class UsageSummary(
    @ColumnInfo(name = "totalRequests") val totalRequests: Int,
    @ColumnInfo(name = "totalSessions") val totalSessions: Int,
    @ColumnInfo(name = "totalSuccess") val totalSuccess: Int,
    @ColumnInfo(name = "totalFail") val totalFail: Int,
    @ColumnInfo(name = "totalInputTokens") val totalInputTokens: Int,
    @ColumnInfo(name = "totalOutputTokens") val totalOutputTokens: Int,
    @ColumnInfo(name = "estimatedCostYuan") val estimatedCostYuan: Double,
    @ColumnInfo(name = "avgLatencyMs") val avgLatencyMs: Long,
) {
    val successRate: Float
        get() = if (totalRequests <= 0) -1f else totalSuccess.toFloat() / totalRequests

    companion object {
        fun empty(): UsageSummary = UsageSummary(0, 0, 0, 0, 0, 0, 0.0, 0L)
    }
}

data class DailyUsagePoint(
    @ColumnInfo(name = "dateBucket") val dateBucket: Long,
    @ColumnInfo(name = "requestCount") val requestCount: Int,
    @ColumnInfo(name = "inputTokens") val inputTokens: Int,
    @ColumnInfo(name = "outputTokens") val outputTokens: Int,
    @ColumnInfo(name = "estimatedCostYuan") val estimatedCostYuan: Double,
    @ColumnInfo(name = "successCount") val successCount: Int,
    @ColumnInfo(name = "failCount") val failCount: Int,
)

data class SuccessBreakdown(
    @ColumnInfo(name = "successCount") val successCount: Int,
    @ColumnInfo(name = "failOther") val failOther: Int,
)

data class ModelDetailStat(
    @ColumnInfo(name = "requestCount") val requestCount: Int,
    @ColumnInfo(name = "sessionCount") val sessionCount: Int,
    @ColumnInfo(name = "successCount") val successCount: Int,
    @ColumnInfo(name = "failCount") val failCount: Int,
    @ColumnInfo(name = "inputTokens") val inputTokens: Int,
    @ColumnInfo(name = "outputTokens") val outputTokens: Int,
    @ColumnInfo(name = "estimatedCostYuan") val estimatedCostYuan: Double,
    @ColumnInfo(name = "avgLatencyMs") val avgLatencyMs: Long,
    @ColumnInfo(name = "p50LatencyMs") val p50LatencyMs: Long,
    @ColumnInfo(name = "p95LatencyMs") val p95LatencyMs: Long,
) {
    val successRate: Float
        get() = if (requestCount <= 0) -1f else successCount.toFloat() / requestCount
}

// 软件签名：TRAE AI 开发环境
// 大模型签名：Trae 智能助手
