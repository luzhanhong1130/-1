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
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * DeepSeek 用量抓取：
 *   `GET /user/balance` → 余额（只读展示，不写入每日明细）
 *   `GET /user/billing/record?start_date=&end_date=` → 按日计费明细
 *
 * DeepSeek 官方 API 返回货币单位为 **CNY（人民币元）**，这是社区实践确认的主流情况。
 */
class DeepSeekUsageFetcher(
    client: OkHttpClient,
    json: Json,
) : BaseUsageFetcher(client, json), UsageFetcher {

    override suspend fun doFetch(
        apiKey: String,
        baseUrl: String,
        fromMillis: Long,
        toMillis: Long,
    ): FetchedBundle {
        val root = baseUrl.ifBlank { "https://api.deepseek.com" }.trimEnd('/')
            .let { if (it.contains("deepseek.com")) it else "https://api.deepseek.com" }

        // 并行（2 个独立请求）——实际用串行也行，但 deepseek billing 接口有时略慢，
        // 这里跑 suspend 里用阻塞调用的串行即可，避免引入过多依赖。
        val start = BillingDateUtils.toIsoDateLocal(fromMillis)
        val end = BillingDateUtils.toIsoDateLocal(toMillis - 1L)

        val billingUrl = root.toHttpUrl()
            .newBuilder()
            .addPathSegments("user/billing/record")
            .addQueryParameter("start_date", start)
            .addQueryParameter("end_date", end)
            .build()
        val req = Request.Builder()
            .url(billingUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

        return exec(req) { resp ->
            val body = resp.readBodyAsStringOrNull()
            val (s, m) = httpStatusToFetchStatus(resp.code, resp.message)
            if (s != FetchStatus.OK) {
                return@exec FetchedBundle.failed(s, m ?: body?.take(200))
            }
            val obj = body.parseJsonOrNull()
                ?: return@exec FetchedBundle.failed(
                    FetchStatus.PARSE_ERR,
                    "DeepSeek billing/record 返回空 JSON"
                )

            // DeepSeek 常见响应结构（以文档为准；若格式变，按最宽容错读）：
            // { "success": true, "data": { "list": [ { "date":"2025-01-02", "models": [{...}], "total_cny": ... } ] } }
            // 或者 { "data": [ { "date":"2025-01-02", ... } ] }
            val rootData = obj.objectOrNull("data") ?: obj
            val list = rootData.arrayOrNull("list") ?: rootData.arrayOrNull("items")
                ?: rootData.arrayOrNull("records")

            val dailyPoints = mutableListOf<RawDailyPoint>()
            var sumReq = 0L
            var sumIn = 0L
            var sumOut = 0L
            var sumCost = 0.0

            if (list != null) {
                for (dayElem in list) {
                    val dayObj = runCatching { dayElem.jsonObject }.getOrNull() ?: continue
                    val dateStr = dayObj.stringOrNull("date")
                        ?: dayObj.stringOrNull("billing_date")
                        ?: continue
                    val dayBucket = parseDateToLocalBucket(dateStr) ?: continue
                    val models = dayObj.arrayOrNull("models")
                        ?: dayObj.arrayOrNull("breakdown")
                    if (models == null) {
                        // 单日聚合，无明细：用 fake modelId = "__deepseek_aggregate__"
                        val cost = doubleOrCompat(dayObj, "total_cny")
                            ?: doubleOrCompat(dayObj, "cost")
                            ?: doubleOrCompat(dayObj, "total_cost")
                            ?: 0.0
                        val r = longOrCompat(dayObj, "requests") ?: 0L
                        val inT = longOrCompat(dayObj, "input_tokens") ?: 0L
                        val outT = longOrCompat(dayObj, "output_tokens") ?: 0L
                        dailyPoints += RawDailyPoint(
                            rawModelId = "__deepseek_aggregate__",
                            dateBucket = dayBucket,
                            requests = r,
                            inputTokens = inT,
                            outputTokens = outT,
                            costAmount = cost,
                            costCurrency = "CNY",
                        )
                        sumReq += r; sumIn += inT; sumOut += outT; sumCost += cost
                        continue
                    }
                    for (mElem in models) {
                        val mObj = runCatching { mElem.jsonObject }.getOrNull() ?: continue
                        val li = readLineItem(mObj) ?: continue
                        val costCur = mObj.stringOrNull("currency")?.uppercase() ?: "CNY"
                        val costAmount = doubleOrCompat(mObj, "cost_cny")
                            ?: doubleOrCompat(mObj, "cost_amount")
                            ?: doubleOrCompat(mObj, "cost")
                            ?: li.cost
                        dailyPoints += RawDailyPoint(
                            rawModelId = li.rawModelId,
                            dateBucket = dayBucket,
                            requests = li.requests,
                            inputTokens = li.inputTokens,
                            outputTokens = li.outputTokens,
                            costAmount = costAmount,
                            costCurrency = costCur,
                        )
                        sumReq += li.requests
                        sumIn += li.inputTokens
                        sumOut += li.outputTokens
                        sumCost += costAmount
                    }
                }
            }

            Log.d(tag, "DeepSeek OK sumCny=$sumCost req=$sumReq in=$sumIn out=$sumOut")
            FetchedBundle(
                status = FetchStatus.OK,
                totalRequests = sumReq,
                totalInputTokens = sumIn,
                totalOutputTokens = sumOut,
                totalCostAmount = sumCost,
                totalCostCurrency = "CNY",
                dailyPoints = dailyPoints,
            )
        }
    }

    private fun parseDateToLocalBucket(isoDate: String): Long? {
        return runCatching {
            val split = isoDate.split('-')
            if (split.size != 3) return@runCatching null
            val y = split[0].toInt()
            val m = split[1].toInt() - 1
            val d = split[2].toInt()
            val cal = java.util.Calendar.getInstance().apply {
                clear()
                set(y, m, d, 0, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        }.getOrNull()
    }
}
