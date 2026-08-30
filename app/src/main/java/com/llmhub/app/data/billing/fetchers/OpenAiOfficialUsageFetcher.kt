package com.llmhub.app.data.billing.fetchers

import android.util.Log
import com.llmhub.app.data.billing.BillingDateUtils
import com.llmhub.app.data.billing.FetchStatus
import com.llmhub.app.data.billing.FetchedBundle
import com.llmhub.app.data.billing.RawDailyPoint
import com.llmhub.app.data.billing.UsageFetcher
import com.llmhub.app.data.remote.arrayOrNull
import com.llmhub.app.data.remote.objectOrNull
import com.llmhub.app.data.remote.stringOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OpenAI 官方 Billing 抓取。
 *
 * 策略（两级 fallback）：
 * 1. 优先 **`GET https://api.openai.com/dashboard/billing/usage?start_date=&end_date=`**（半官方但多年稳定）
 *    - 返回 `total_usage`（美分，/100 → USD）；`daily_costs[].timestamp + line_items[]`
 *    - 若 HTTP 401/403：直接走 AUTH_FAIL，不 fallback。
 *    - 若 HTTP 404（组织未开放此 path），走 step2。
 * 2. 回退 **`GET /v1/organization/costs?start_time=&end_time=&bucket_width=1d&group_by=model`**
 *    - 新版 Admin API（organization owner 才有权限；普通 Key 可能 404/403）。
 *    - 读 `data[].start_time / end_time`；`results[].model/input_tokens/output_tokens/num_model_requests/cost?`
 *
 * 两种路径的费用都是 **USD**（step1 美分→USD 除以 100；step2 cost_usd 已是 USD）。
 */
class OpenAiOfficialUsageFetcher(
    client: OkHttpClient,
    json: Json,
) : BaseUsageFetcher(client, json), UsageFetcher {

    override suspend fun doFetch(
        apiKey: String,
        baseUrl: String,
        fromMillis: Long,
        toMillis: Long,
    ): FetchedBundle {
        // Step 1
        return when (val res1 = fetchBillingUsageLegacy(apiKey, fromMillis, toMillis)) {
            // auth fail 不回退
            is StepResult.StatusOnly -> when (res1.status) {
                FetchStatus.AUTH_FAIL, FetchStatus.RATE_LIMITED, FetchStatus.NETWORK ->
                    FetchedBundle.failed(res1.status, res1.err)
                FetchStatus.PARSE_ERR, FetchStatus.UNKNOWN -> {
                    // 404 或其它非致命：走 step2
                    fetchOrgCostsFallback(apiKey, fromMillis, toMillis)
                        ?: FetchedBundle.failed(res1.status, res1.err)
                }
                FetchStatus.OK -> error("StepResult.StatusOnly 不会是 OK")
                FetchStatus.NOT_SUPPORTED ->
                    FetchedBundle.notSupported()
            }
            is StepResult.Bundle -> res1.value
        }
    }

    private sealed class StepResult {
        data class StatusOnly(val status: FetchStatus, val err: String? = null) : StepResult()
        data class Bundle(val value: FetchedBundle) : StepResult()
    }

    private fun fetchBillingUsageLegacy(
        apiKey: String,
        fromMillis: Long,
        toMillis: Long,
    ): StepResult {
        val url = "https://api.openai.com".toHttpUrl()
            .newBuilder()
            .addPathSegments("dashboard/billing/usage")
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
            val (s, m) = httpStatusToFetchStatus(resp.code, resp.message)
            if (s != FetchStatus.OK) {
                if (resp.code == 404) {
                    // 让外层 fallback 走 organization/costs
                    return@exec StepResult.StatusOnly(
                        FetchStatus.PARSE_ERR,
                        m ?: "billing/usage 404，尝试 organization/costs",
                    )
                }
                return@exec StepResult.StatusOnly(s, m ?: body?.take(200))
            }
            val obj = body.parseJsonOrNull()
                ?: return@exec StepResult.StatusOnly(
                    FetchStatus.PARSE_ERR,
                    "dashboard/billing/usage 返回空 JSON",
                )

            // 美分 → USD
            val totalCents = doubleOrCompat(obj, "total_usage") ?: 0.0
            val totalCostUsd = totalCents / 100.0

            val dailyCostsArr = obj.arrayOrNull("daily_costs")
            val dailyPoints = mutableListOf<RawDailyPoint>()
            var sumReq = 0L
            var sumIn = 0L
            var sumOut = 0L
            var sumCostUsd = 0.0
            if (dailyCostsArr != null) {
                for (dayElement in dailyCostsArr) {
                    val dayObj = runCatching { dayElement.jsonObject }.getOrNull() ?: continue
                    val timestamp = longOrCompat(dayObj, "timestamp")
                        ?: intOrCompat(dayObj, "timestamp")?.toLong() ?: continue
                    val dayBucket = BillingDateUtils.unixSecondsToLocalBucket(timestamp)
                    val lineItems = dayObj.arrayOrNull("line_items") ?: JsonArray(emptyList())
                    for (liElem in lineItems) {
                        val liObj = runCatching { liElem.jsonObject }.getOrNull() ?: continue
                        val li = readLineItem(liObj) ?: continue
                        // line_items.cost 多数平台也是美分 → USD；但不同版本有差异，做最大兼容：
                        // 如果 cost > 1 且 total_usage/100 远小于 sum，则认为 cost 已经是 USD，不除；
                        // 否则按美分 / 100。启发式：假设每日 cost 不会超过 50 USD。
                        val maybeCent = li.cost
                        val costUsd = if (maybeCent > 50) maybeCent / 100.0 else maybeCent
                        dailyPoints += RawDailyPoint(
                            rawModelId = li.rawModelId,
                            dateBucket = dayBucket,
                            requests = li.requests,
                            inputTokens = li.inputTokens,
                            outputTokens = li.outputTokens,
                            costAmount = costUsd,
                            costCurrency = "USD",
                        )
                        sumReq += li.requests
                        sumIn += li.inputTokens
                        sumOut += li.outputTokens
                        sumCostUsd += costUsd
                    }
                }
            }

            val finalCost = if (sumCostUsd > 0.0) sumCostUsd else totalCostUsd
            Log.d(tag, "OpenAI legacy OK costUsd=$finalCost req=$sumReq in=$sumIn out=$sumOut")
            StepResult.Bundle(
                FetchedBundle(
                    status = FetchStatus.OK,
                    totalRequests = sumReq,
                    totalInputTokens = sumIn,
                    totalOutputTokens = sumOut,
                    totalCostAmount = finalCost,
                    totalCostCurrency = "USD",
                    dailyPoints = dailyPoints,
                )
            )
        }
    }

    private fun fetchOrgCostsFallback(
        apiKey: String,
        fromMillis: Long,
        toMillis: Long,
    ): FetchedBundle? {
        val startSec = fromMillis / 1000L
        val endSec = (toMillis + 999L) / 1000L
        val url = "https://api.openai.com".toHttpUrl()
            .newBuilder()
            .addPathSegments("v1/organization/costs")
            .addQueryParameter("start_time", startSec.toString())
            .addQueryParameter("end_time", endSec.toString())
            .addQueryParameter("bucket_width", "1d")
            .addQueryParameter("group_by", "model")
            .build()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()
        return runCatching {
            exec(req) { resp ->
                val body = resp.readBodyAsStringOrNull()
                val (s, m) = httpStatusToFetchStatus(resp.code, resp.message)
                if (s != FetchStatus.OK) {
                    // organization/costs 权限通常需要组织 owner；多数用户 403 正常，这里不记 AUTH_FAIL 级错误
                    return@exec null
                }
                val obj = body.parseJsonOrNull() ?: return@exec null
                val dataArr = obj.arrayOrNull("data") ?: return@exec null
                val dailyPoints = mutableListOf<RawDailyPoint>()
                var sumReq = 0L; var sumIn = 0L; var sumOut = 0L; var sumCost = 0.0
                for (bucket in dataArr) {
                    val bObj = runCatching { bucket.jsonObject }.getOrNull() ?: continue
                    val startTs = longOrCompat(bObj, "start_time") ?: continue
                    val dayBucket = BillingDateUtils.unixSecondsToLocalBucket(startTs)
                    val resultsArr = bObj.arrayOrNull("results") ?: continue
                    for (rElem in resultsArr) {
                        val r = runCatching { rElem.jsonObject }.getOrNull() ?: continue
                        val model = r.stringOrNull("model") ?: "__unknown__"
                        val req = longOrCompat(r, "num_model_requests") ?: 0L
                        val inT = longOrCompat(r, "input_tokens") ?: 0L
                        val outT = longOrCompat(r, "output_tokens") ?: 0L
                        val cost = doubleOrCompat(r, "cost_usd")
                            ?: doubleOrCompat(r, "cost_amount") ?: 0.0
                        dailyPoints += RawDailyPoint(
                            rawModelId = model,
                            dateBucket = dayBucket,
                            requests = req,
                            inputTokens = inT,
                            outputTokens = outT,
                            costAmount = cost,
                            costCurrency = "USD",
                        )
                        sumReq += req; sumIn += inT; sumOut += outT; sumCost += cost
                    }
                }
                Log.d(tag, "OpenAI org/costs fallback OK sum=$sumCost")
                FetchedBundle(
                    status = FetchStatus.OK,
                    totalRequests = sumReq,
                    totalInputTokens = sumIn,
                    totalOutputTokens = sumOut,
                    totalCostAmount = sumCost,
                    totalCostCurrency = "USD",
                    dailyPoints = dailyPoints,
                )
            }
        }.getOrNull()
    }
}
