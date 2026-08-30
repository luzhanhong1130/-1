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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 通义千问 DashScope 用量抓取。
 *
 * DashScope 无官方 Billing REST；官方路径：
 *   `POST https://dashscope.aliyuncs.com/api/v1/bills/query?startDate=&endDate=&NextToken=&MaxItems=100`
 *   鉴权：`Authorization: Bearer <DashScope-API-KEY>`，Header 中附加 `X-DashScope-Async: enable`。
 *   注意：官方实际上需要阿里云 STS Token；普通用户只有 DashScope Key 可能拿不到账单明细。
 *
 * 这里按「社区已知兼容路径 + 最大容错」实现：
 *  1. 优先 `GET /bills?startDate=&endDate=` 走 Bearer（部分账户可返回 JSON）
 *  2. 若 401/403 且未读到明细：返回 AUTH_FAIL，ComparePanel 提示用户「DashScope 需要阿里云 STS，暂不支持普通 Key 查询账单」
 *
 * 返回币别 DashScope 官方文档为 CNY（人民币元）。
 */
class DashScopeUsageFetcher(
    client: OkHttpClient,
    json: Json,
) : BaseUsageFetcher(client, json), UsageFetcher {

    override suspend fun doFetch(
        apiKey: String,
        baseUrl: String,
        fromMillis: Long,
        toMillis: Long,
    ): FetchedBundle {
        val root = baseUrl.ifBlank { "https://dashscope.aliyuncs.com" }.trimEnd('/')
        val startDate = BillingDateUtils.toIsoDateLocal(fromMillis)
        val endDate = BillingDateUtils.toIsoDateLocal(toMillis - 1L)

        // Path 1: GET /api/v1/bills?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
        val getUrl = root.toHttpUrl()
            .newBuilder()
            .addPathSegments("api/v1/bills")
            .addQueryParameter("startDate", startDate)
            .addQueryParameter("endDate", endDate)
            .addQueryParameter("MaxItems", "100")
            .build()
        val getReq = Request.Builder()
            .url(getUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("X-DashScope-Async", "enable")
            .header("Accept", "application/json")
            .get()
            .build()

        val getRes = runCatching { tryParse(getReq) }
        if (getRes.isFailure) {
            val th = getRes.exceptionOrNull()
            return FetchedBundle.failed(
                FetchStatus.NETWORK,
                th?.message ?: "DashScope 网络错误",
            )
        }
        val bundle = getRes.getOrNull()
        if (bundle != null) return bundle

        // Path 2: POST /api/v1/bills/query（JSON body，阿里云 STS 路径）
        val postUrl = root.toHttpUrl()
            .newBuilder()
            .addPathSegments("api/v1/bills/query")
            .addQueryParameter("startDate", startDate)
            .addQueryParameter("endDate", endDate)
            .build()
        val body = """{"startDate":"$startDate","endDate":"$endDate","maxItems":100}"""
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val postReq = Request.Builder()
            .url(postUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("X-DashScope-Async", "enable")
            .header("Accept", "application/json")
            .post(body)
            .build()

        val postBundle = runCatching { tryParse(postReq) }.getOrNull()
        if (postBundle != null && postBundle.status == FetchStatus.OK) return postBundle

        // 两条路径都没拿到 200 OK+明细：给用户一个清晰的 NOT_SUPPORTED 提示，避免反复刷屏
        return FetchedBundle(
            status = FetchStatus.NOT_SUPPORTED,
            errorMessage = "DashScope 账单需要阿里云 STS 临时凭证，普通 DashScope Key 暂无法查询明细。请稍后在模型编辑里切换为 DISABLED 或使用 One API 中转抓取。",
        )
    }

    private fun tryParse(req: Request): FetchedBundle? {
        return exec(req) { resp ->
            val body = resp.readBodyAsStringOrNull()
            val (s, m) = httpStatusToFetchStatus(resp.code, resp.message)
            if (s == FetchStatus.AUTH_FAIL) {
                // AUTH_FAIL 不吞掉，直接返回（否则用户以为自己 key 对）
                return@exec FetchedBundle.failed(FetchStatus.AUTH_FAIL, m ?: body?.take(200))
            }
            if (s != FetchStatus.OK) return@exec null
            val obj = body.parseJsonOrNull() ?: return@exec null
            // DashScope 返回结构常为 { "code":"", "message":"", "data":{ "items":[{...}], "totalCount":N } }
            // 也可能是 { "RequestId": "...", "Bills":[{...}] }（阿里云 OpenAPI 风格）
            val items = obj.arrayOrNull("items")
                ?: obj.objectOrNull("data")?.arrayOrNull("items")
                ?: obj.arrayOrNull("Bills")
                ?: obj.objectOrNull("Data")?.arrayOrNull("Bills")
                ?: return@exec null
            val daily = mutableListOf<RawDailyPoint>()
            var sumReq = 0L; var sumIn = 0L; var sumOut = 0L; var sumCost = 0.0
            for (elem in items) {
                val b = runCatching { elem.jsonObject }.getOrNull() ?: continue
                val dateStr = b.stringOrNull("billDate")
                    ?: b.stringOrNull("date")
                    ?: b.stringOrNull("StatDate")
                    ?: BillingDateUtils.toIsoDateLocal(BillingDateUtils.todayBucketLocal())
                val dayBucket = BillingDateUtils.startOfDayLocal(
                    parseDashDate(dateStr) ?: BillingDateUtils.todayBucketLocal()
                )
                val model = b.stringOrNull("model")
                    ?: b.stringOrNull("modelId")
                    ?: b.stringOrNull("ModelName")
                    ?: "__dashscope_aggregate__"
                val costCny = doubleOrCompat(b, "pretaxAmount")
                    ?: doubleOrCompat(b, "amount")
                    ?: doubleOrCompat(b, "CostAmount")
                    ?: 0.0
                val inT = longOrCompat(b, "inputTokens")
                    ?: longOrCompat(b, "prompt_tokens") ?: 0L
                val outT = longOrCompat(b, "outputTokens")
                    ?: longOrCompat(b, "completion_tokens") ?: 0L
                val r = longOrCompat(b, "requests")
                    ?: intOrCompat(b, "callCnt")?.toLong() ?: 0L
                daily += RawDailyPoint(
                    rawModelId = model,
                    dateBucket = dayBucket,
                    requests = r,
                    inputTokens = inT,
                    outputTokens = outT,
                    costAmount = costCny,
                    costCurrency = "CNY",
                )
                sumReq += r; sumIn += inT; sumOut += outT; sumCost += costCny
            }
            if (daily.isEmpty()) return@exec null
            Log.d(tag, "DashScope OK sumCny=$sumCost days=${daily.groupBy { it.dateBucket }.size}")
            FetchedBundle(
                status = FetchStatus.OK,
                totalRequests = sumReq,
                totalInputTokens = sumIn,
                totalOutputTokens = sumOut,
                totalCostAmount = sumCost,
                totalCostCurrency = "CNY",
                dailyPoints = daily,
            )
        }
    }

    private fun parseDashDate(s: String): Long? {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return runCatching { fmt.parse(s)?.time }.getOrNull()
    }
}
