package com.llmhub.app.data.billing

import com.llmhub.app.ui.stats.TimeRange
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Billing 子系统的日期/时间助手。
 *
 * 为了兼容 One API / OpenAI 等多数平台「按本地自然日」计费习惯，日期格式化
 * 统一走**设备本地时区**；日桶 startOfDay 也按本地时区计算。
 */
object BillingDateUtils {

    /** 把 [millis] 截到当日 00:00:00.000（本地时区）。 */
    fun startOfDayLocal(millis: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /** 今日 00:00:00（本地时区） 毫秒。 */
    fun todayBucketLocal(): Long = startOfDayLocal(System.currentTimeMillis())

    /** 格式化 millis → YYYY-MM-DD（本地时区，Query String 用）。 */
    fun toIsoDateLocal(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return fmt.format(Date(millis))
    }

    /** 格式化 millis → HH:mm（本地时区，UI 显示 fetchedAt 用）。 */
    fun toHourMinuteLocal(millis: Long): String {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return fmt.format(Date(millis))
    }

    /** 解析 ISO 日期字符串 "yyyy-MM-dd" → 本地时区当日 00:00 毫秒；失败返回 null。 */
    fun parseIsoDateOrNull(s: String): Long? {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return runCatching { fmt.parse(s.trim())?.time?.let { startOfDayLocal(it) } }.getOrNull()
    }

    /** 当前时间 → "yyyy-MM-dd HH:mm" 本地时区字符串（MANUAL 参考快照备注用）。 */
    fun formatNowLocal(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return fmt.format(Date(System.currentTimeMillis()))
    }

    /** 把 TimeRange 转成 [fromMillis, toMillis] 闭开区间（toMillis 取明天 00:00，覆盖当日）。 */
    fun rangeToMillis(range: TimeRange): Pair<Long, Long> {
        val from = range.fromMillis()
        // to = 明天 00:00，让查询范围覆盖到「今天最后一毫秒」
        val to = startOfDayLocal(System.currentTimeMillis()) + DAY_MS
        return from to to
    }

    /**
     * 把 One API / OpenAI 常见 `seconds_since_epoch * 1000` 转
     * 本地日桶（方便写入 RemoteDailyPoint.dateBucket）。
     */
    fun unixSecondsToLocalBucket(seconds: Long): Long =
        startOfDayLocal(seconds * 1000L)

    /** 设备离线检查 6 小时内不刷新缓存。毫秒常量。 */
    const val CACHE_TTL_MS: Long = 6L * 60 * 60 * 1000

    private const val DAY_MS: Long = 24L * 60 * 60 * 1000

    /** 清理 60 天以前的旧快照：毫秒常量。 */
    const val KEEP_DAYS_MS: Long = 60L * DAY_MS
}
