package com.llmhub.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.llmhub.app.data.billing.BillingDateUtils
import com.llmhub.app.ui.stats.TimeRange
import java.text.SimpleDateFormat
import java.util.Locale

enum class PlatformBillingKind(val displayName: String) {
    DISABLED("暂不启用"),
    OPENAI_OFFICIAL("OpenAI 官方"),
    ONE_API("One API 中转"),
    DEEPSEEK("DeepSeek 官方"),
    DASHSCOPE("通义 DashScope"),
    ;
    companion object {
        val selectable: List<PlatformBillingKind> = listOf(
            DISABLED, ONE_API, OPENAI_OFFICIAL, DEEPSEEK, DASHSCOPE,
        )
    }
}

val PlatformBillingKind.supportsWebLogin: Boolean
    get() = when (this) {
        PlatformBillingKind.ONE_API, PlatformBillingKind.DEEPSEEK -> true
        else -> false
    }

fun PlatformBillingKind.webLoginStartUrl(baseUrlOverride: String?): String? =
    when (this) {
        PlatformBillingKind.ONE_API -> baseUrlOverride?.trimEnd('/')?.takeIf { it.isNotBlank() }
        PlatformBillingKind.DEEPSEEK -> "https://platform.deepseek.com"
        else -> null
    }

fun PlatformBillingKind.webUsageUrl(
    baseUrlOverride: String?,
    timeRange: TimeRange,
): String? {
    val (from, to) = BillingDateUtils.rangeToMillis(timeRange)
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val fromIso = fmt.format(java.util.Date(from))
    val toIso = fmt.format(java.util.Date(to))
    return when (this) {
        PlatformBillingKind.ONE_API -> {
            val base = baseUrlOverride?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
            "$base/#/dashboard/billing?start_date=$fromIso&end_date=$toIso"
        }
        PlatformBillingKind.DEEPSEEK -> {
            "https://platform.deepseek.com/user/billing/record?startDate=$fromIso&endDate=$toIso"
        }
        else -> null
    }
}

fun PlatformBillingKind.webPostLoginIndicatorPrefix(baseUrlOverride: String?): String? =
    when (this) {
        PlatformBillingKind.ONE_API -> baseUrlOverride?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { "$it/#/chat" }
        PlatformBillingKind.DEEPSEEK -> "https://platform.deepseek.com/chat"
        else -> null
    }

@Entity(
    tableName = "remote_usage_snapshots",
    indices = [Index(value = ["apiKeyRefId", "fetchedAtDayBucket"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = ApiKeyConfig::class,
        parentColumns = ["id"],
        childColumns = ["apiKeyRefId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class RemoteUsageSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val apiKeyRefId: Long,
    val rangeStartMillis: Long,
    val rangeEndMillis: Long,
    val fetchedAtDayBucket: Long,
    val fetchedAtMillis: Long,
    val status: String,
    val errorMessage: String? = null,
    val totalRequests: Long = 0L,
    val totalInputTokens: Long = 0L,
    val totalOutputTokens: Long = 0L,
    val totalCostAmount: Double = 0.0,
    val totalCostCurrency: String = "CNY",
    val source: String = SOURCE_API,
    val note: String? = null,
) {
    companion object {
        const val SOURCE_API = "API"
        const val SOURCE_WEB = "WEB"
        const val SOURCE_MANUAL = "MANUAL"
    }
}

@Entity(
    tableName = "remote_daily_points",
    foreignKeys = [ForeignKey(
        entity = RemoteUsageSnapshot::class,
        parentColumns = ["id"],
        childColumns = ["snapshotId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["snapshotId", "dateBucket", "modelId"], unique = true)],
)
data class RemoteDailyPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val snapshotId: Long,
    @ColumnInfo(name = "modelId") val modelId: String,
    @ColumnInfo(name = "dateBucket") val dateBucket: Long,
    val requests: Long = 0L,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val costAmount: Double = 0.0,
    val costCurrency: String = "CNY",
)

// 软件签名：TRAE AI 开发环境
// 大模型签名：Trae 智能助手
