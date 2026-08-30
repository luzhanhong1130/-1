package com.llmhub.app.data.billing.web

import android.util.Log
import android.webkit.WebView
import com.llmhub.app.data.billing.BillingDateUtils
import com.llmhub.app.data.billing.FetchStatus
import com.llmhub.app.data.billing.FetchedBundle
import com.llmhub.app.data.billing.RawDailyPoint
import com.llmhub.app.data.billing.normalizeModelId
import com.llmhub.app.data.model.PlatformBillingKind
import com.llmhub.app.ui.stats.TimeRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebView 抽取编排器：在当前已加载的 WebView 页面里执行一段 JS（fetch localStorage 的 Key、
 * 访问 DOM、读取 `__NEXT_DATA__` 等），将结果统一封装为 [FetchedBundle]。
 *
 * 稳定性优先级：
 *   1. 用 localStorage Key 直接发起 `fetch('/v1/...usage')`（JSON 结构化，最稳）
 *   2. 读 SPA 内嵌 JSON（`window.__NEXT_DATA__` / `#__NEXT_DATA__`）
 *   3. DOM 文本解析（最后兜底）
 *   4. 以上都失败 → 返回 `PARSE_ERR`，UI 保留 Web 视图供用户肉眼对账。
 */
@Singleton
class WebExtractOrchestrator @Inject constructor(
    private val json: Json,
) {

    companion object {
        private const val TAG = "WebExtractOrch"
        private val DAY_FMT: SimpleDateFormat by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    }

    // ------------------------------------------------------------------
    // 对外 API
    // ------------------------------------------------------------------

    suspend fun extract(
        kind: PlatformBillingKind,
        webView: WebView,
        range: TimeRange,
        baseUrlOverride: String?,
        normalizedModelKeySet: Set<String>,
    ): FetchedBundle {
        return when (kind) {
            PlatformBillingKind.ONE_API -> extractOneApi(webView, range, baseUrlOverride, normalizedModelKeySet)
            PlatformBillingKind.DEEPSEEK -> extractDeepSeek(webView, range, normalizedModelKeySet)
            else -> FetchedBundle.failed(
                FetchStatus.NOT_SUPPORTED,
                "当前平台未提供 Web 抽取实现（${kind.displayName}）",
            )
        }
    }

    // ------------------------------------------------------------------
    // One API：优先 localStorage token → fetch('/v1/dashboard/billing/usage')
    // ------------------------------------------------------------------

    private suspend fun extractOneApi(
        webView: WebView,
        range: TimeRange,
        baseUrlOverride: String?,
        normalizedModelKeySet: Set<String>,
    ): FetchedBundle {
        val base = (baseUrlOverride?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: extractCurrentOrigin(webView)?.trimEnd('/'))
            ?: return FetchedBundle.failed(
                FetchStatus.PARSE_ERR,
                "One API Web 登录无法确定 Base URL：请先在模型配置里填写 Base URL，或者当前页面先打开中转站首页。"
            )

        // 从 localStorage/sessionStorage/cookie 里猜 token。
        val tokenScript = """
            (function() {
                function tryJSON(src) {
                    try { return JSON.stringify(src); } catch(e) { return null; }
                }
                // localStorage 常见 key 名（不同 fork 不同）：
                var keys = ['token','access_token','authorization','Authorization','api-key','apiKey','apikey','oneapi_token'];
                for (var i = 0; i < keys.length; i++) {
                    var v = null;
                    try { v = window.localStorage.getItem(keys[i]); } catch(e) {}
                    if (!v) try { v = window.sessionStorage.getItem(keys[i]); } catch(e) {}
                    if (v && typeof v === 'string' && v.length > 6) {
                        return JSON.stringify({source:'localStorage', key: keys[i], value: v});
                    }
                }
                // 兜底：在 document.cookie 里找 token=xxx
                try {
                    var c = document.cookie || '';
                    var segs = c.split(/;\s*/);
                    for (var j = 0; j < segs.length; j++) {
                        var kv = segs[j].split('=');
                        if (kv.length === 2 && /token|auth|session/i.test(kv[0])) {
                            return JSON.stringify({source:'cookie', key: kv[0], value: decodeURIComponent(kv[1])});
                        }
                    }
                } catch(e) {}
                return JSON.stringify({source:'none', key: '', value: ''});
            })();
        """.trimIndent()

        val tokenRaw = evalJsOrNull(webView, tokenScript)
        val tokenParsed = runCatching { tokenRaw?.let { json.parseToJsonElement(it.trim('"')).jsonObject } }.getOrNull()
        val token = tokenParsed?.get("value")?.jsonPrimitive?.contentOrNull?.trim('"')?.takeIf { it.isNotBlank() }

        if (token == null) {
            // 没找到 Key，降级：直接从 DOM 读 /dashboard/billing 的表格文本（最松散兜底）
            return extractOneApiDomFallback(webView, range, normalizedModelKeySet, base)
        }

        // 找到 token → 发 fetch，和 OneApiUsageFetcher 相同逻辑
        val (from, to) = BillingDateUtils.rangeToMillis(range)
        val start = DAY_FMT.format(Date(from))
        val end = DAY_FMT.format(Date(to))
        val fetchScript = """
            (async function() {
                try {
                    var url = '$base/v1/dashboard/billing/usage?start_date=$start&end_date=$end';
                    var resp = await fetch(url, {
                        method: 'GET',
                        headers: {
                            'Authorization': 'Bearer $token',
                            'Accept': 'application/json'
                        },
                        credentials: 'same-origin'
                    });
                    var body = await resp.text();
                    return JSON.stringify({ok: resp.ok, status: resp.status, body: body});
                } catch (e) {
                    return JSON.stringify({ok: false, status: 0, body: 'fetch_error: ' + String(e)});
                }
            })();
        """.trimIndent()

        val respRaw = evalJsOrNull(webView, fetchScript)
        val parsed = runCatching { respRaw?.let { json.parseToJsonElement(it.trim('"')).jsonObject } }.getOrNull()
            ?: return FetchedBundle.failed(
                FetchStatus.PARSE_ERR,
                "One API Web fetch 返回值无法解析：${respRaw?.take(200) ?: "null"}"
            )
        val ok = parsed["ok"]?.jsonPrimitive?.booleanOrNull == true
        val statusCode = parsed["status"]?.jsonPrimitive?.intOrNullCompat() ?: 0
        val body = parsed["body"]?.jsonPrimitive?.contentOrNull?.trim('"').orEmpty()

        if (!ok) {
            val statusWhenFail = when {
                statusCode in 401..403 -> FetchStatus.AUTH_FAIL
                statusCode == 429 -> FetchStatus.RATE_LIMITED
                statusCode in 500..599 -> FetchStatus.NETWORK
                body.startsWith("fetch_error") -> FetchStatus.NETWORK
                else -> FetchStatus.PARSE_ERR
            }
            return FetchedBundle.failed(statusWhenFail, "One API 网页 fetch 失败：HTTP $statusCode ${body.take(200)}")
        }

        // 复用 OneApiUsageFetcher 已有的 JSON → FetchedBundle 逻辑不方便（它要 OkHttp 初始化），
        // 这里直接做轻量解析。字段和 OneApiUsageFetcher 一致：total_usage / daily_costs / line_items
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return FetchedBundle.failed(
                FetchStatus.PARSE_ERR,
                "One API JSON 解析失败：${body.take(120)}"
            )

        val totalCents = root["total_usage"]?.jsonPrimitive?.run { content.toDoubleOrNull() ?: doubleOrNull } ?: 0.0
        val currency = "CNY"
        val totalCostYuan = totalCents * 0.0001 // One API 返回的是 10000 分制的 cents
        val dailyArr = root["daily_costs"]?.jsonArray ?: emptyList()
        val lineItemsArr = root["line_items"]?.jsonArray ?: emptyList()

        val dailyPoints = ArrayList<RawDailyPoint>(dailyArr.size + lineItemsArr.size)
        // 按日：timestamp(unix 秒) / token_usage / cost(10000 分制) / model_name?
        for (el in dailyArr) {
            val o = el as? JsonObject ?: continue
            val ts = o["timestamp"]?.jsonPrimitive?.longOrNullCompat() ?: continue
            val bucket = BillingDateUtils.unixSecondsToLocalBucket(ts)
            val tokens = o["token_usage"]?.jsonPrimitive?.longOrNullCompat() ?: 0L
            val costTenThousand = o["cost"]?.jsonPrimitive?.doubleOrNullCompat() ?: 0.0
            val modelNameRaw = o["model_name"]?.jsonPrimitive?.contentOrNull
            val modelName = modelNameRaw?.takeIf {
                normalizedModelKeySet.isEmpty() || normalizeModelId(it) in normalizedModelKeySet
            }
            dailyPoints += RawDailyPoint(
                dateBucket = bucket,
                rawModelId = modelName ?: "oneapi_web_aggregate",
                inputTokens = tokens, // One API 没拆 input/output，这里全算 input 作为近似
                outputTokens = 0L,
                requests = 0L,
                costAmount = costTenThousand * 0.0001,
                costCurrency = currency,
            )
        }
        // line_items：更细粒度（可能比 daily 先有），不覆盖 daily，直接作为补充。
        for (el in lineItemsArr) {
            val o = el as? JsonObject ?: continue
            val costTenThousand = o["cost"]?.jsonPrimitive?.doubleOrNullCompat() ?: continue
            val tokens = o["input_tokens"]?.jsonPrimitive?.longOrNullCompat() ?: 0L
            val tokensOut = o["output_tokens"]?.jsonPrimitive?.longOrNullCompat() ?: 0L
            val name = (o["name"]?.jsonPrimitive?.contentOrNull
                ?: o["model"]?.jsonPrimitive?.contentOrNull)
                ?: continue
            if (normalizedModelKeySet.isNotEmpty() && normalizeModelId(name) !in normalizedModelKeySet) continue
            // line_items 没有按日分；把它挂到 range 的第一天
            dailyPoints += RawDailyPoint(
                dateBucket = BillingDateUtils.startOfDayLocal(from),
                rawModelId = name,
                inputTokens = tokens,
                outputTokens = tokensOut,
                requests = 0L,
                costAmount = costTenThousand * 0.0001,
                costCurrency = currency,
            )
        }
        val totalTokens = dailyPoints.sumOf { it.inputTokens + it.outputTokens }
        val totalRequests = dailyPoints.sumOf { it.requests }

        return FetchedBundle(
            status = FetchStatus.OK,
            errorMessage = null,
            totalRequests = totalRequests,
            totalInputTokens = dailyPoints.sumOf { it.inputTokens },
            totalOutputTokens = dailyPoints.sumOf { it.outputTokens },
            totalCostAmount = totalCostYuan,
            totalCostCurrency = currency,
            dailyPoints = dailyPoints,
        )
    }

    private suspend fun extractOneApiDomFallback(
        webView: WebView,
        range: TimeRange,
        normalizedModelKeySet: Set<String>,
        base: String,
    ): FetchedBundle {
        val (from, to) = BillingDateUtils.rangeToMillis(range)
        val script = """
            (function() {
                // 最松散：抽取 document.body.innerText；App 端再尝试正则匹配。
                return JSON.stringify({
                    location: location.href,
                    title: document.title,
                    innerText: (document.body && document.body.innerText) ? document.body.innerText.slice(0, 65536) : ''
                });
            })();
        """.trimIndent()
        val raw = evalJsOrNull(webView, script)
        val parsed = runCatching { raw?.let { json.parseToJsonElement(it.trim('"')).jsonObject } }.getOrNull()
        val innerText = parsed?.get("innerText")?.jsonPrimitive?.contentOrNull.orEmpty()
        val location = parsed?.get("location")?.jsonPrimitive?.contentOrNull ?: base
        // 简单启发：按 regex 找 "¥ 123.45" / "CNY 123.45" / "总费用 123.45"
        val costMatch = Regex("""(?:总费用|合计|总计|总消耗|CNY|¥|RMB)\s*[:：]?\s*([0-9]+(?:\.[0-9]+)?)""")
            .find(innerText)?.groupValues?.get(1)?.toDoubleOrNull()
        val tokenMatch = Regex("""(?:总 Token|Token 总数|tokens?|总调用量)\s*[:：]?\s*([0-9][0-9,\.]+)""", RegexOption.IGNORE_CASE)
            .find(innerText)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()?.toLong()
        return if (costMatch == null && tokenMatch == null) {
            FetchedBundle.failed(
                FetchStatus.PARSE_ERR,
                "未在当前页面识别到费用/Token 数据。请先登录并打开「Dashboard → 用量/账单」页再抽取。"
            )
        } else {
            FetchedBundle(
                status = FetchStatus.OK,
                errorMessage = "DOM 文本抽取：仅整段聚合（$location），无法按日拆分。",
                totalRequests = 0L,
                totalInputTokens = tokenMatch ?: 0L,
                totalOutputTokens = 0L,
                totalCostAmount = costMatch ?: 0.0,
                totalCostCurrency = "CNY",
                dailyPoints = listOf(
                    RawDailyPoint(
                        dateBucket = BillingDateUtils.startOfDayLocal(from),
                        rawModelId = "oneapi_web_dom_aggregate",
                        inputTokens = tokenMatch ?: 0L,
                        outputTokens = 0L,
                        requests = 0L,
                        costAmount = costMatch ?: 0.0,
                        costCurrency = "CNY",
                    )
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // DeepSeek：优先读 __NEXT_DATA__ / __INITIAL_STATE__ 内嵌 JSON
    // ------------------------------------------------------------------

    private suspend fun extractDeepSeek(
        webView: WebView,
        range: TimeRange,
        normalizedModelKeySet: Set<String>,
    ): FetchedBundle {
        val (from, to) = BillingDateUtils.rangeToMillis(range)
        val script = """
            (function() {
                function safe(obj) {
                    try { return JSON.stringify(obj); } catch(e) { return null; }
                }
                // 1) window.__NEXT_DATA__（Next.js 默认 hydrated）
                if (window.__NEXT_DATA__) return JSON.stringify({from:'next', data: safe(window.__NEXT_DATA__)});
                // 2) #__NEXT_DATA__ 节点
                var el = document && document.getElementById('__NEXT_DATA__');
                if (el && el.textContent) return JSON.stringify({from:'next_tag', data: el.textContent});
                // 3) window.__INITIAL_STATE__（Vue 场景偶尔会有）
                if (window.__INITIAL_STATE__) return JSON.stringify({from:'init', data: safe(window.__INITIAL_STATE__)});
                // 4) fallback：fetch 同站的 billing/record?startDate=...&endDate=...（Cookie 带上）
                try {
                    return new Promise(function(resolve) {
                        var u = new URL(window.location.href);
                        var target = u.origin + '/user/billing/record?startDate=' +
                            '${DAY_FMT.format(Date(from))}' + '&endDate=' + '${DAY_FMT.format(Date(to))}';
                        fetch(target, {credentials:'include', headers:{'Accept':'application/json'}})
                            .then(function(r){return r.text();})
                            .then(function(t){resolve(JSON.stringify({from:'fetch', data: t, ok: true, status: 200, url: target}));})
                            .catch(function(e){resolve(JSON.stringify({from:'fetch', ok:false, error: String(e)});});
                    });
                } catch (e2) {
                    return JSON.stringify({from:'none'});
                }
            })();
        """.trimIndent()

        val raw = evalJsOrNull(webView, script) ?: return FetchedBundle.failed(
            FetchStatus.PARSE_ERR,
            "DeepSeek 抽取脚本返回空。请先登录并进入「账单记录」页。",
        )

        val wrapper = runCatching { json.parseToJsonElement(raw.trim('"')).jsonObject }.getOrNull()
            ?: return FetchedBundle.failed(
                FetchStatus.PARSE_ERR, "DeepSeek 抽取脚本返回无法解析：${raw.take(160)}"
            )

        val fromTag = wrapper["from"]?.jsonPrimitive?.contentOrNull
        val dataStr = wrapper["data"]?.jsonPrimitive?.contentOrNull
        val fetchOk = wrapper["ok"]?.jsonPrimitive?.booleanOrNull
        val fetchStatus = wrapper["status"]?.jsonPrimitive?.intOrNullCompat()

        if (fromTag == "none" || (fromTag == "fetch" && fetchOk != true)) {
            return FetchedBundle.failed(
                FetchStatus.PARSE_ERR,
                "在当前页面找不到 billing/record JSON（from=$fromTag）。请先进入 DeepSeek 官网的「用户中心 → 账单记录」并确保已登录。"
            )
        }

        // fetch 失败的 HTTP 层级诊断
        if (fromTag == "fetch" && fetchStatus != null && fetchStatus >= 400) {
            val fs = when (fetchStatus) {
                401, 403 -> FetchStatus.AUTH_FAIL
                429 -> FetchStatus.RATE_LIMITED
                in 500..599 -> FetchStatus.NETWORK
                else -> FetchStatus.PARSE_ERR
            }
            return FetchedBundle.failed(fs, "DeepSeek fetch 返回 HTTP $fetchStatus")
        }

        val data: JsonObject = runCatching {
            dataStr?.let { json.parseToJsonElement(it.trim('"')).jsonObject }
        }.getOrNull()
            ?: return FetchedBundle.failed(FetchStatus.PARSE_ERR, "DeepSeek JSON 载荷不是对象")

        // Next.js 路径：props.pageProps.xxx 或 props -> initialState
        val records = findDeepSeekRecordsArray(data)
        val currency = findDeepSeekCurrency(data) ?: "CNY"
        val bucketFrom = BillingDateUtils.startOfDayLocal(from)
        val bucketTo = BillingDateUtils.startOfDayLocal(to)

        val dailyPoints = ArrayList<RawDailyPoint>(records.size)
        var aggrCost = 0.0
        var aggrTokens = 0L
        var aggrReq = 0L
        for (rec in records) {
            val dateStr = (rec["date"] ?: rec["billDate"] ?: rec["day"])?.jsonPrimitive?.contentOrNull
            val bucket: Long = dateStr
                ?.let { s: String -> BillingDateUtils.parseIsoDateOrNull(s) }
                ?.let { b: Long -> BillingDateUtils.startOfDayLocal(b) }
                ?: continue
            if (bucket !in bucketFrom..bucketTo) continue
            val modelIdNullable = (rec["model"] ?: rec["modelId"] ?: rec["model_name"])?.jsonPrimitive?.contentOrNull
            if (normalizedModelKeySet.isNotEmpty() && modelIdNullable != null && normalizeModelId(modelIdNullable) !in normalizedModelKeySet) {
                continue
            }
            val promptT = (rec["prompt_tokens"] ?: rec["inputTokens"] ?: rec["promptTokens"])?.jsonPrimitive?.longOrNullCompat()
                ?: 0L
            val compT = (rec["completion_tokens"] ?: rec["outputTokens"] ?: rec["completionTokens"])?.jsonPrimitive?.longOrNullCompat()
                ?: 0L
            val reqs = (rec["requests"] ?: rec["numberOfCalls"])?.jsonPrimitive?.longOrNullCompat() ?: 0L
            val costUnit = (rec["cost"] ?: rec["cost_amount"] ?: rec["totalFeeAmount"]
                ?: rec["totalCost"])?.jsonPrimitive?.doubleOrNullCompat() ?: 0.0
            val unit = (rec["currency"] ?: rec["costUnit"] ?: rec["feeUnit"])?.jsonPrimitive?.contentOrNull
            val converted = convertDeepSeekCost(costUnit, unit, currency)
            aggrCost += converted
            aggrTokens += promptT + compT
            aggrReq += reqs
            dailyPoints += RawDailyPoint(
                dateBucket = bucket,
                rawModelId = modelIdNullable ?: "deepseek_web_aggregate",
                inputTokens = promptT,
                outputTokens = compT,
                requests = reqs,
                costAmount = converted,
                costCurrency = currency,
            )
        }

        // 兼容：没按日数组时，从 summary / total 层级取总费用
        if (dailyPoints.isEmpty()) {
            val totalCost = (data["total_amount"] ?: data["totalCost"] ?: data["summary"]?.jsonObject?.let {
                it["total_cost"] ?: it["totalAmount"] ?: it["total"]
            })?.jsonPrimitive?.doubleOrNullCompat()
            val totalTokens = (data["total_tokens"] ?: data["summary"]?.jsonObject?.get("total_tokens"))
                ?.jsonPrimitive?.longOrNullCompat()
            if (totalCost != null || totalTokens != null) {
                dailyPoints += RawDailyPoint(
                    dateBucket = BillingDateUtils.startOfDayLocal(from),
                    rawModelId = "deepseek_web_summary",
                    inputTokens = totalTokens ?: 0L,
                    outputTokens = 0L,
                    requests = 0L,
                    costAmount = totalCost ?: 0.0,
                    costCurrency = currency,
                )
                aggrCost = totalCost ?: 0.0
                aggrTokens = totalTokens ?: 0L
            }
        }

        return FetchedBundle(
            status = FetchStatus.OK,
            errorMessage = if (dailyPoints.isEmpty()) "未找到符合当前时间范围的账单记录。请检查页面日期范围是否与统计页匹配。" else null,
            totalRequests = aggrReq,
            totalInputTokens = dailyPoints.sumOf { it.inputTokens },
            totalOutputTokens = dailyPoints.sumOf { it.outputTokens },
            totalCostAmount = aggrCost,
            totalCostCurrency = currency,
            dailyPoints = dailyPoints,
        )
    }

    // ------------------------------------------------------------------
    // 辅助：脚本执行 / 查找账单数组 / 币种换算 / intOrNull 兼容
    // ------------------------------------------------------------------

    private suspend fun evalJsOrNull(webView: WebView, script: String): String? =
        runCatching { WebCookieManager.evaluateJs(webView, script) }.fold(
            onSuccess = { it?.takeUnless { it == "null" } },
            onFailure = {
                Log.w(TAG, "evalJs failed: ${it.message}")
                null
            }
        )

    private suspend fun extractCurrentOrigin(webView: WebView): String? =
        evalJsOrNull(webView, "(function(){return location.origin;})();")?.trim('"')

    private fun findDeepSeekRecordsArray(obj: JsonObject): List<JsonObject> {
        // 1. 根下 records
        obj["records"]?.jsonArray?.let { a -> return a.mapNotNull { it as? JsonObject } }
        // 2. Next.js：props.pageProps.records / props.pageProps.initialRecords
        val props = obj["props"]?.jsonObject
        val pageProps = props?.get("pageProps")?.jsonObject
        pageProps?.get("records")?.jsonArray?.let { a -> return a.mapNotNull { it as? JsonObject } }
        pageProps?.get("initialRecords")?.jsonArray?.let { a -> return a.mapNotNull { it as? JsonObject } }
        pageProps?.get("rows")?.jsonArray?.let { a -> return a.mapNotNull { it as? JsonObject } }
        // 3. query API 响应：{code:200, data:{records:[]}} 或 {data:{list:[]}}
        val payloadData = obj["data"]?.jsonObject
        payloadData?.get("records")?.jsonArray?.let { a -> return a.mapNotNull { it as? JsonObject } }
        payloadData?.get("rows")?.jsonArray?.let { a -> return a.mapNotNull { it as? JsonObject } }
        payloadData?.get("list")?.jsonArray?.let { a -> return a.mapNotNull { it as? JsonObject } }
        // 4. query API 响应直接 {records}（已被 #1 捕获；此处仅兜底 billingDetails）
        obj["billingDetails"]?.jsonArray?.let { a -> return a.mapNotNull { it as? JsonObject } }
        return emptyList()
    }

    private fun findDeepSeekCurrency(obj: JsonObject): String? {
        (obj["currency"] ?: obj["cost_currency"])?.jsonPrimitive?.contentOrNull?.let { return it }
        (obj["data"]?.jsonObject?.get("currency"))?.jsonPrimitive?.contentOrNull?.let { return it }
        val props = obj["props"]?.jsonObject?.get("pageProps")?.jsonObject
        (props?.get("currency") ?: props?.get("defaultCurrency"))?.jsonPrimitive?.contentOrNull?.let { return it }
        return null
    }

    private fun convertDeepSeekCost(rawAmount: Double, rawUnit: String?, targetCurrency: String): Double {
        val u = rawUnit?.trim()?.uppercase() ?: targetCurrency
        val yuan = when (u) {
            "YUAN", "RMB", "CNY", "¥" -> rawAmount
            "CENT", "FEN" -> rawAmount * 0.01
            "JIAO", "MAO" -> rawAmount * 0.1
            "USD", "$" -> rawAmount * 7.2 // 粗略固定汇率：只用于估算展示，真实对账以官方为准
            "HKD", "HK$" -> rawAmount * 0.92
            "EUR", "€" -> rawAmount * 7.8
            "GBP", "£" -> rawAmount * 9.3
            "JPY", "JP¥" -> rawAmount * 0.046
            else -> rawAmount // 未知单位：原样返回，提示靠 currency 列显示原生单位
        }
        return when (targetCurrency.uppercase()) {
            "CNY", "YUAN", "RMB", "¥" -> yuan
            "USD", "$" -> yuan / 7.2
            "HKD", "HK$" -> yuan / 0.92
            "EUR", "€" -> yuan / 7.8
            "GBP", "£" -> yuan / 9.3
            "JPY", "JP¥" -> yuan / 0.046
            else -> yuan
        }
    }
}

// ------------------------------------------------------------------
// 本文件内 JSON 读取兼容小扩展（避免与 BaseUsageFetcher 二义性，全部走 content 解析）
// ------------------------------------------------------------------

private fun kotlinx.serialization.json.JsonPrimitive.intOrNullCompat(): Int? {
    val c = content.trim().trim('"').trim('\'')
    return c.toIntOrNull() ?: c.toDoubleOrNull()?.toInt()
}

private fun kotlinx.serialization.json.JsonPrimitive.longOrNullCompat(): Long? {
    val c = content.trim().trim('"').trim('\'')
    return c.toLongOrNull() ?: c.toDoubleOrNull()?.toLong()
}

private fun kotlinx.serialization.json.JsonPrimitive.doubleOrNullCompat(): Double? {
    val c = content.trim().trim('"').trim('\'').replace(",", "")
    return c.toDoubleOrNull()
}
