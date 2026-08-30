package com.llmhub.app.data.billing.fetchers

import android.util.Log
import com.llmhub.app.data.billing.FetchStatus
import com.llmhub.app.data.billing.FetchedBundle
import com.llmhub.app.data.remote.arrayOrNull
import com.llmhub.app.data.remote.objectOrNull
import com.llmhub.app.data.remote.stringOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 四个平台共用的抓取基类：HTTP 状态码映射、错误兜底、Json 容错读取助手。
 *
 * 每个子类实现 [doFetch]，外层 [fetch] 负责 catch IOException + 通用 HTTP 映射。
 */
abstract class BaseUsageFetcher(
    protected val client: OkHttpClient,
    protected val json: Json,
) {
    protected val tag: String = javaClass.simpleName

    suspend fun fetch(
        apiKey: String,
        baseUrl: String,
        fromMillis: Long,
        toMillis: Long,
    ): FetchedBundle {
        return runCatching { doFetch(apiKey, baseUrl, fromMillis, toMillis) }
            .getOrElse { th ->
                Log.w(tag, "fetch failed: ${th.message}", th)
                when (th) {
                    is IOException ->
                        FetchedBundle.failed(FetchStatus.NETWORK, (th.message ?: "网络错误").truncate())
                    else ->
                        FetchedBundle.failed(FetchStatus.UNKNOWN, (th.message ?: "未知错误").truncate())
                }
            }
    }

    /** 子类实现：做真正的 HTTP 调用，返回 FetchedBundle。 */
    protected abstract suspend fun doFetch(
        apiKey: String,
        baseUrl: String,
        fromMillis: Long,
        toMillis: Long,
    ): FetchedBundle

    // ------------------------------------------------------------
    // 共用工具
    // ------------------------------------------------------------

    /** OK HTTP 标准执行 + Response 闭包。 */
    protected inline fun <R> exec(request: Request, block: (Response) -> R): R =
        client.newCall(request).execute().use(block)

    /** HTTP 码 → FetchStatus 粗映射（401/403=鉴权；429=限流；5xx=NETWORK；其他=PARSE_ERR 配合 info）。 */
    protected fun httpStatusToFetchStatus(code: Int, msg: String?): Pair<FetchStatus, String?> =
        when {
            code == 401 || code == 403 ->
                FetchStatus.AUTH_FAIL to (msg ?: "API Key 无权访问用量接口（HTTP $code）")
            code == 429 ->
                FetchStatus.RATE_LIMITED to "平台限流，请稍后重试（HTTP 429）"
            code in 500..599 ->
                FetchStatus.NETWORK to (msg ?: "平台异常（HTTP $code）")
            code in 200..299 ->
                FetchStatus.OK to null
            else ->
                FetchStatus.PARSE_ERR to (msg ?: "意外的响应（HTTP $code）")
        }

    protected fun Response.readBodyAsStringOrNull(): String? =
        runCatching { body?.string() }.getOrNull()

    protected fun String?.parseJsonOrNull(): JsonObject? {
        if (this == null) return null
        return runCatching { json.parseToJsonElement(this).jsonObject }
            .onFailure { Log.w(tag, "parse json failed: ${it.message}") }
            .getOrNull()
    }

    protected fun String.truncate(max: Int = 512): String =
        if (length <= max) this else substring(0, max)

    // ---- 2-arg 读取辅助：匹配所有 Fetcher 内的 longOrCompat(obj, "key") / intOrCompat / doubleOrCompat 调用风格 ----

    /** 容错读 Long：支持 JSON 数字字段或字符串 "123"。统一通过 `content` 解析，避免 `longOrNull` property/function 二义性。 */
    protected fun longOrCompat(obj: JsonObject?, key: String): Long? {
        val content = obj?.get(key)?.jsonPrimitive?.content ?: return null
        return content.toLongOrNull() ?: content.toDoubleOrNull()?.toLong()
    }

    protected fun intOrCompat(obj: JsonObject?, key: String): Int? {
        val content = obj?.get(key)?.jsonPrimitive?.content ?: return null
        return content.toIntOrNull() ?: content.toDoubleOrNull()?.toInt()
    }

    protected fun doubleOrCompat(obj: JsonObject?, key: String): Double? {
        val content = obj?.get(key)?.jsonPrimitive?.content ?: return null
        return content.toDoubleOrNull()
    }

    /** One API / OpenAI 通用的 line_items 单条容错读取：{name, model, cost, input_tokens? ...}。 */
    protected fun readLineItem(obj: JsonObject?): LineItem? {
        if (obj == null) return null
        val cost = doubleOrCompat(obj, "cost")
            ?: doubleOrCompat(obj, "cost_amount")
            ?: 0.0
        val name = obj.stringOrNull("name")
        val model = obj.stringOrNull("model")
            ?: obj.stringOrNull("model_id")
            ?: name
            ?: "__unknown__"
        val inputTokens = longOrCompat(obj, "input_tokens")
            ?: longOrCompat(obj, "prompt_tokens")
            ?: longOrCompat(obj, "n_context_tokens_total")
            ?: 0L
        val outputTokens = longOrCompat(obj, "output_tokens")
            ?: longOrCompat(obj, "completion_tokens")
            ?: longOrCompat(obj, "n_generated_tokens_total")
            ?: 0L
        val requests = longOrCompat(obj, "requests")
            ?: intOrCompat(obj, "num_model_requests")?.toLong()
            ?: longOrCompat(obj, "num_requests")
            ?: 0L
        return LineItem(
            rawModelId = model,
            requests = requests,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cost = cost,
        )
    }

    protected data class LineItem(
        val rawModelId: String,
        val requests: Long,
        val inputTokens: Long,
        val outputTokens: Long,
        val cost: Double,
    )
}
