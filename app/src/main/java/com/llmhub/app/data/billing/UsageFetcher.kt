package com.llmhub.app.data.billing

import com.llmhub.app.data.model.PlatformBillingKind
import com.llmhub.app.data.billing.fetchers.DashScopeUsageFetcher
import com.llmhub.app.data.billing.fetchers.DeepSeekUsageFetcher
import com.llmhub.app.data.billing.fetchers.OneApiUsageFetcher
import com.llmhub.app.data.billing.fetchers.OpenAiOfficialUsageFetcher
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 平台用量抓取的统一契约：给定 API Key + Base Url + 时间区间，
 * 返回 [FetchedBundle]（状态码+聚合+按日明细）。
 *
 * 所有实现都运行在非 UI 线程（由 Repository `withContext(IO)` 调度），
 * 内部允许使用 OkHttp 的 **阻塞** `newCall.execute()`。
 */
interface UsageFetcher {
    suspend fun fetch(
        apiKey: String,
        baseUrl: String,
        fromMillis: Long,
        toMillis: Long,
    ): FetchedBundle
}

/**
 * DISABLED 占位实现：直接返回 NOT_SUPPORTED 状态，让 Repository 不发起 HTTP。
 */
internal object DisabledUsageFetcher : UsageFetcher {
    override suspend fun fetch(
        apiKey: String,
        baseUrl: String,
        fromMillis: Long,
        toMillis: Long,
    ): FetchedBundle = FetchedBundle.notSupported()
}

/**
 * 为每个请求短超时复制一份 OkHttpClient：
 * NetworkModule 单例为 SSE 设置了 5 分钟读超时，Billing 接口不需要。
 */
internal fun OkHttpClient.forBilling(): OkHttpClient =
    newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false) // 失败由 Repository 上层决定是否重试，避免双重试
        .build()

/**
 * 工厂：把 PlatformBillingKind 映射成对应 UsageFetcher。
 * 不用 Hilt multibinds（对于 4 条 small-class 分支太重），直接 when 就行。
 */
fun PlatformBillingKind.toFetcher(client: OkHttpClient, json: Json): UsageFetcher {
    val billingClient = client.forBilling()
    return when (this) {
        PlatformBillingKind.ONE_API -> OneApiUsageFetcher(billingClient, json)
        PlatformBillingKind.OPENAI_OFFICIAL -> OpenAiOfficialUsageFetcher(billingClient, json)
        PlatformBillingKind.DEEPSEEK -> DeepSeekUsageFetcher(billingClient, json)
        PlatformBillingKind.DASHSCOPE -> DashScopeUsageFetcher(billingClient, json)
        PlatformBillingKind.DISABLED -> DisabledUsageFetcher
    }
}
