package com.llmhub.app.data.billing.fetchers

import android.util.Log
import com.llmhub.app.data.billing.BillingDateUtils
import com.llmhub.app.data.billing.BillingDateUtils.rangeToMillis
import com.llmhub.app.data.billing.FetchStatus
import com.llmhub.app.data.billing.FetchedBundle
import com.llmhub.app.data.billing.RawDailyPoint
import com.llmhub.app.data.billing.UsageFetcher
import com.llmhub.app.data.remote.arrayOrNull
import com.llmhub.app.data.remote.stringOrNull
import com.llmhub.app.ui.stats.TimeRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One API / New API / Laisky-One-API 等兼容社区平台：
 * `GET {baseUrl}/v1/dashboard/billing/usage?start_date=YYYY-MM-DD&end_date=YYYY-MM-DD`
 *
 * 该接口和 OpenAI 半官方 billing/usage 高度兼容：返回 `total_usage`、
 * `daily_costs[]`（每条 `{timestamp, line_items[]}`）和 `line_items[]`
 * （每条 `{name/model/cost/input_tokens?/output_tokens?}`）。
 *
 * 大多数 One API 实例返回的费用币种是 **CNY（人民币 元或配额分数）**，
 * 这里统一以 `totalCostCurrency = "CNY"` 存储。对于细分单位差异（分 vs 元），
 * 社区实现并不一致，所以 ComparePanel 会原样显示，不做换算。
 */
class OneApiUsageFetcher(
    client: OkHttpClient,
    json: Json,
) : BaseUsageFetcher(client, json), UsageFetcher {

    override suspend fun doFetch(
        apiKey: String,
        baseUrl: String,
        fromMillis: Long,
        toMillis: Long,
    ): FetchedBundle {
        val url = baseUrl.trimEnd('/')
            .toHttpUrl()
            .newBuilder()
            .addPathSegments("v1/dashboard/billing/usage")
            .addQueryParameter("start_date", BillingDateUtils.toIsoDateLocal(fromMillis))
            .addQueryParameter("end_date", BillingDateUtils.toIsoDateLocal(toMillis - 1L))
            .build()

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

        return exec(req) { resp ->
            val body = resp.readBodyAsStringOrNull()
            val (status0, msg0) = httpStatusToFetchStatus(resp.code, resp.message)
            if (status0 != FetchStatus.OK) {
                return@exec FetchedBundle.failed(status0, msg0 ?: body?.take(200))
            }
            val obj = body.parseJsonOrNull()
                ?: return@exec FetchedBundle.failed(
                    FetchStatus.PARSE_ERR,
                    "One API billing/usage 返回空响应",
                )

            // ---- 聚合：total_usage（美分？配额分？CNY 元？依实例而定，直接按原值存入 costAmount）
            val totalUsage = doubleOrCompat(obj, "total_usage")
                ?: doubleOrCompat(obj, "total_cost")
                ?: doubleOrCompat(obj, "quota_used")
                ?: 0.0

            // ---- 按天拆分：daily_costs[]
            val dailyCostsArr: JsonArray? = obj.arrayOrNull("daily_costs")
            val dailyPoints = mutableListOf<RawDailyPoint>()
            var sumRequests = 0L
            var sumIn = 0L
            var sumOut = 0L
            var sumCost = 0.0

            if (dailyCostsArr != null) {
                for (dayElement in dailyCostsArr) {
                    val dayObj = runCatching { dayElement.jsonObject }.getOrNull() ?: continue
                    val timestamp = longOrCompat(dayObj, "timestamp")
                        ?: intOrCompat(dayObj, "timestamp")?.toLong()
                        ?: continue
                    val dayBucket = BillingDateUtils.unixSecondsToLocalBucket(timestamp)
                    val lineItems = dayObj.arrayOrNull("line_items")
                        ?: JsonArray(emptyList())
                    for (liElem in lineItems) {
                        val liObj = runCatching { liElem.jsonObject }.getOrNull() ?: continue
                        val li = readLineItem(liObj) ?: continue
                        dailyPoints += RawDailyPoint(
                            rawModelId = li.rawModelId,
                            dateBucket = dayBucket,
                            requests = li.requests,
                            inputTokens = li.inputTokens,
                            outputTokens = li.outputTokens,
                            costAmount = li.cost,
                            costCurrency = "CNY",
                        )
                        sumRequests += li.requests
                        sumIn += li.inputTokens
                        sumOut += li.outputTokens
                        sumCost += li.cost
                    }
                }
            }

            // 如果 daily_costs 解析不出聚合值（sumCost=0 但 total_usage 有值），
            // 就把 total_usage 当总费用存入；否则以 daily 求和为准。
            val finalCost = if (sumCost > 0.0) sumCost else totalUsage
            val finalReq = sumRequests
            val finalIn = sumIn
            val finalOut = sumOut

            Log.d(
                tag,
                "fetch OK totalUsage=$totalUsage sumCost=$sumCost " +
                    "req=$finalReq in=$finalIn out=$finalOut days=${dailyPoints.groupBy { it.dateBucket }.size}"
            )

            FetchedBundle(
                status = FetchStatus.OK,
                totalRequests = finalReq,
                totalInputTokens = finalIn,
                totalOutputTokens = finalOut,
                totalCostAmount = finalCost,
                totalCostCurrency = "CNY",
                dailyPoints = dailyPoints,
            )
        }
    }
}
