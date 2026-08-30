package com.llmhub.app.data.billing

/**
 * 单次抓取的状态码（与 [RemoteUsageSnapshot.status] wire 字符串一一对应，
 * 但多了一个 NOT_SUPPORTED 只用于 DISABLED 的场景，落 DB 时也直接写 NOT_SUPPORTED，
 * ComparePanel 读到 status=NOT_SUPPORTED 就显示引导态。
 */
enum class FetchStatus(val wire: String) {
    OK("OK"),
    AUTH_FAIL("AUTH_FAIL"),
    RATE_LIMITED("RATE_LIMITED"),
    NETWORK("NETWORK"),
    PARSE_ERR("PARSE_ERR"),
    NOT_SUPPORTED("NOT_SUPPORTED"),
    UNKNOWN("UNKNOWN"),
    ;

    companion object {
        fun fromWire(wire: String?): FetchStatus =
            values().firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

/**
 * Fetcher 层返回的原始按日点（还未插 snapshotId）。
 *
 * @param rawModelId 平台返回的原始 modelId（带前缀也 OK，Repo 层 normalize 后落库）。
 * @param dateBucket   本地时区 00:00:00 日桶毫秒。
 */
data class RawDailyPoint(
    val rawModelId: String,
    val dateBucket: Long,
    val requests: Long = 0L,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val costAmount: Double = 0.0,
    val costCurrency: String = "CNY",
) {
    init {
        require(dateBucket >= 0L) { "dateBucket must be non-negative, got=$dateBucket" }
    }
}

/**
 * UsageFetcher 返回的聚合结果 + 按日明细 bundle。
 *
 * 字段对齐：
 *  - totalCostAmount / totalCostCurrency：用于 RemoteUsageSnapshot.aggregate 落库；
 *  - dailyPoints：每个 RawDailyPoint 含 modelId + 日桶 + 金额/tokens，Repo 层会 normalizeModelId 再落 DB。
 */
data class FetchedBundle(
    val status: FetchStatus,
    /** 可读错误信息，落 RemoteUsageSnapshot.errorMessage（最多 512 字符，Repo 层截断）。 */
    val errorMessage: String? = null,
    val totalRequests: Long = 0L,
    val totalInputTokens: Long = 0L,
    val totalOutputTokens: Long = 0L,
    val totalCostAmount: Double = 0.0,
    val totalCostCurrency: String = "CNY",
    val dailyPoints: List<RawDailyPoint> = emptyList(),
) {
    companion object {
        /** 快速构造 DISABLED / 暂未启用 的空 bundle。 */
        fun notSupported(msg: String? = null) = FetchedBundle(
            status = FetchStatus.NOT_SUPPORTED,
            errorMessage = msg,
        )

        fun failed(status: FetchStatus, err: String? = null) = FetchedBundle(
            status = status,
            errorMessage = err,
        )
    }
}

/**
 * 规范化模型 ID（让 ComparePanel 日后支持按 model 对齐时匹配更稳定）：
 *  - 去掉 `openai/gpt-4o` 的 `openai/` 前缀 → `gpt-4o`
 *  - 去掉 `azure/gpt-4o` / `provider-xxx` / `openai.` 前缀
 *  - trim + lowercase
 */
fun normalizeModelId(raw: String): String {
    var s = raw.trim().lowercase()
    val slashIdx = s.lastIndexOf('/')
    if (slashIdx in 0 until s.lastIndex) s = s.substring(slashIdx + 1)
    val dotIdx = s.lastIndexOf('.')
    if (dotIdx in 0 until s.lastIndex) s = s.substring(dotIdx + 1)
    return s
}

/** 截断 errorMessage 到 512 字符，避免 SQLite 写入膨胀（RemoteUsageSnapshot.errorMessage 未限制长度，但 UI 不会读太长）。 */
fun String?.truncateErr(): String? {
    if (this == null) return null
    return if (length <= 512) this else substring(0, 512)
}
