package com.llmhub.app.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.llmhub.app.data.billing.BillingDateUtils
import com.llmhub.app.data.billing.BillingDateUtils.CACHE_TTL_MS
import com.llmhub.app.data.billing.FetchStatus
import com.llmhub.app.data.billing.FetchedBundle
import com.llmhub.app.data.billing.RawDailyPoint
import com.llmhub.app.data.billing.UsageFetcher
import com.llmhub.app.data.billing.normalizeModelId
import com.llmhub.app.data.billing.toFetcher
import com.llmhub.app.data.billing.truncateErr
import com.llmhub.app.data.db.dao.RemoteUsageDao
import com.llmhub.app.data.model.ModelConfig
import com.llmhub.app.data.model.PlatformBillingKind
import com.llmhub.app.data.model.RemoteDailyPoint
import com.llmhub.app.data.model.RemoteUsageSnapshot
import com.llmhub.app.data.prefs.SecureKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 远程用量查询仓储：
 *
 *  - 读侧：[observeCompareSide]，返回 ComparePanel 右栏聚合值 + 币种 + 状态；
 *  - 写侧：[forceRefresh]，调用具体 UsageFetcher → 写入 Room（事务）；
 *  - 缓存：6 小时 [CACHE_TTL_MS] 内同一 (config + range) 复用最新快照，[forceRefresh(ignoreTTL=true)] 才重刷；
 *  - 离线预检：无网络时快速返回 NOT_SUPPORTED+NETWORK 状态，ComparePanel 提示离线；
 *  - 清理：[purgeOldSnapshots] 清掉 N 天以前的旧快照。
 *
 * 单进程里每个 (modelConfigId) 一次只允许一个 in-flight 刷新；用户快速连点「立即同步」
 * 不会并发打爆平台限流。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class RemoteUsageRepository @Inject constructor(
    private val dao: RemoteUsageDao,
    private val okhttp: OkHttpClient,
    private val json: Json,
    private val secureKeyStore: SecureKeyStore,
    @ApplicationContext private val ctx: Context,
) {

    private val fetcherCache: ConcurrentHashMap<PlatformBillingKind, UsageFetcher> =
        ConcurrentHashMap()

    /** (modelConfigId) → 正在刷新的信号，用于 UI 侧展示 Loading + 避免并发刷。 */
    internal val inflightById: MutableStateFlow<Set<Long>> = MutableStateFlow(emptySet())

    private fun fetcherOf(kind: PlatformBillingKind): UsageFetcher =
        fetcherCache.getOrPut(kind) { kind.toFetcher(okhttp, json) }

    // ------------------------------------------------------------------
    // 读侧：给 UsageViewModel 的 compare side 聚合流
    // ------------------------------------------------------------------

    /**
     * UI 用：观察 (config + range + normalizedModelKey) → 右栏 CompareSideValues。
     *
     * 规则：
     *  - config = null 或 billingKind=DISABLED：返回 ZERO；
     *  - 找不到快照：返回 ZERO；
     *  - 按 normalizedModelKey 过滤：若当前选中模型归一化后名字匹配上 remote 某个点的 modelId，则用该点求和；
     *    否则用 snapshot 的聚合字段（整段时间所有模型，用于全局对比）。
     */
    fun observeCompareSide(
        config: ModelConfig?,
        fromMillis: Long,
        toMillis: Long,
        normalizedModelKey: String?,
    ): Flow<RemoteCompareView> {
        if (config == null) return flowOf(RemoteCompareView.ZERO)
        if (config.billingEndpointKind == PlatformBillingKind.DISABLED) {
            return flowOf(RemoteCompareView.ZERO.copy(billingKind = config.billingEndpointKind))
        }
        val apiKeyRefId = config.id
        return combine(
            dao.observeLatestSnapshot(apiKeyRefId, fromMillis, toMillis),
            inflightById,
        ) { snap, inflight ->
            val source = snap?.source
            val note = snap?.note
            Pair(snap, (apiKeyRefId in inflight) to (source to note))
        }.flatMapLatest { (snap, extras) ->
            val (isLoading, sourceNote) = extras
            val source = sourceNote.first
            val note = sourceNote.second
            if (snap == null) {
                flowOf(
                    RemoteCompareView(
                        requests = 0L,
                        inputTokens = 0L,
                        outputTokens = 0L,
                        costAmount = 0.0,
                        costCurrency = "CNY",
                        billingKind = config.billingEndpointKind,
                        fetchedAtMillis = 0L,
                        status = null,
                        errorMessage = null,
                        isLoading = isLoading,
                        source = source,
                        note = note,
                    )
                )
            } else {
                // 如果 normalizedModelKey 给定 → 从 daily 点过滤按模型汇总；否则用 snapshot 聚合
                val filteredFlow: Flow<RemoteCompareView> = if (normalizedModelKey.isNullOrBlank()) {
                    flowOf(
                        RemoteCompareView(
                            requests = snap.totalRequests,
                            inputTokens = snap.totalInputTokens,
                            outputTokens = snap.totalOutputTokens,
                            costAmount = snap.totalCostAmount,
                            costCurrency = snap.totalCostCurrency,
                            billingKind = config.billingEndpointKind,
                            fetchedAtMillis = snap.fetchedAtMillis,
                            status = snap.status,
                            errorMessage = snap.errorMessage,
                            isLoading = isLoading,
                            source = source ?: snap.source,
                            note = note ?: snap.note,
                        )
                    )
                } else {
                    dao.observeDailyInRange(snap.id, fromMillis, toMillis)
                        .map { pts ->
                            val matching = pts.filter { normalizeModelId(it.modelId) == normalizedModelKey }
                            if (matching.isEmpty()) {
                                // 没匹配到该模型的明细，就用 snapshot 聚合（避免显示全为 0，合理）
                                RemoteCompareView(
                                    requests = snap.totalRequests,
                                    inputTokens = snap.totalInputTokens,
                                    outputTokens = snap.totalOutputTokens,
                                    costAmount = snap.totalCostAmount,
                                    costCurrency = snap.totalCostCurrency,
                                    billingKind = config.billingEndpointKind,
                                    fetchedAtMillis = snap.fetchedAtMillis,
                                    status = snap.status,
                                    errorMessage = snap.errorMessage,
                                    isLoading = isLoading,
                                    source = source ?: snap.source,
                                    note = note ?: snap.note,
                                )
                            } else {
                                val sumR = matching.sumOf { it.requests }
                                val sumI = matching.sumOf { it.inputTokens }
                                val sumO = matching.sumOf { it.outputTokens }
                                val sumC = matching.sumOf { it.costAmount }
                                val cur = matching.firstOrNull()?.costCurrency ?: snap.totalCostCurrency
                                RemoteCompareView(
                                    requests = sumR,
                                    inputTokens = sumI,
                                    outputTokens = sumO,
                                    costAmount = sumC,
                                    costCurrency = cur,
                                    billingKind = config.billingEndpointKind,
                                    fetchedAtMillis = snap.fetchedAtMillis,
                                    status = snap.status,
                                    errorMessage = null,
                                    isLoading = isLoading,
                                    source = source ?: snap.source,
                                    note = note ?: snap.note,
                                )
                            }
                        }
                }
                filteredFlow
            }
        }.distinctUntilChanged()
    }

    // ------------------------------------------------------------------
    // 写侧：forceRefresh / ingestWebBundle / markManualSnapshotReference
    // ------------------------------------------------------------------

    /**
     * 将 WebView 抽取得到的 [FetchedBundle] 写入 DB（source=WEB）。
     * 不经过 API Key 校验、不做 TTL 判断——由用户在 Web 面板上手动确认后调用。
     */
    suspend fun ingestWebBundle(
        config: ModelConfig,
        fromMillis: Long,
        toMillis: Long,
        bundle: FetchedBundle,
    ): Unit = withContext(Dispatchers.IO) {
        persistAndReturnStatus(
            config = config,
            fromMillis = fromMillis,
            toMillis = toMillis,
            bundle = bundle,
            source = com.llmhub.app.data.model.RemoteUsageSnapshot.SOURCE_WEB,
        )
    }

    /**
     * 记录一次「用户已查看网页对账」的快照占位（source=MANUAL，不写 daily 点）。
     * 用于 ComparePanel 顶部展示 `stats_banner_manual_reference` 参考提示。
     */
    suspend fun markManualSnapshotReference(
        config: ModelConfig,
        fromMillis: Long,
        toMillis: Long,
        note: String,
    ): Unit = withContext(Dispatchers.IO) {
        // 构造空 bundle：status=OK 避免 ComparePanel 顶部显示 error banner，
        // errorMessage 保留 note 作为冗余信息，但真正展示用的是 snapshot.note
        val emptyBundle = FetchedBundle(
            status = com.llmhub.app.data.billing.FetchStatus.OK,
            errorMessage = note.take(512),
            totalRequests = 0L,
            totalInputTokens = 0L,
            totalOutputTokens = 0L,
            totalCostAmount = 0.0,
            totalCostCurrency = "CNY",
            dailyPoints = emptyList(),
        )
        persistAndReturnStatus(
            config = config,
            fromMillis = fromMillis,
            toMillis = toMillis,
            bundle = emptyBundle,
            source = com.llmhub.app.data.model.RemoteUsageSnapshot.SOURCE_MANUAL,
            note = note.take(512),
        )
    }

    /**
     * @return 写入完成后的最新状态（OK/AUTH_FAIL 等）；若被并发同 key 抢占，返回 null 表示交由并发协程完成。
     */
    suspend fun forceRefresh(
        config: ModelConfig,
        fromMillis: Long,
        toMillis: Long,
        ignoreTTL: Boolean = false,
    ): FetchStatus? = withContext(Dispatchers.IO) {
        val key = config.id
        // 原子抢占：compareAndSet 循环保证「检查 + 占位」是原子的，
        // 否则两个协程同时通过 if (key in prev) 检查会并发打两次平台限流接口。
        var entered = false
        while (!entered) {
            val prev = inflightById.value
            if (key in prev) return@withContext null
            if (inflightById.compareAndSet(prev, prev + key)) entered = true
        }
        try {
            doForceRefresh(config, fromMillis, toMillis, ignoreTTL)
        } finally {
            inflightById.value = inflightById.value - key
        }
    }

    private suspend fun doForceRefresh(
        config: ModelConfig,
        fromMillis: Long,
        toMillis: Long,
        ignoreTTL: Boolean,
    ): FetchStatus {
        val billingKind = config.billingEndpointKind
        val apiKey: String = secureKeyStore.loadKey(config.apiKeyRefId).orEmpty()
        if (apiKey.isBlank()) {
            persistAndReturnStatus(
                config = config,
                fromMillis = fromMillis,
                toMillis = toMillis,
                bundle = FetchedBundle.failed(
                    FetchStatus.AUTH_FAIL,
                    "请先在「密钥」中为该模型关联一个可用的 API Key",
                ),
            )
            return FetchStatus.AUTH_FAIL
        }
        val fetcher = fetcherOf(billingKind)

        // 1) TTL 检查：6h 内有最近 OK 快照且用户没按「强制刷新」→ 直接复用
        if (!ignoreTTL) {
            val lastOk = dao.getLatestSnapshot(config.id, fromMillis, toMillis)
            val age = System.currentTimeMillis() - (lastOk?.fetchedAtMillis ?: 0L)
            if (lastOk != null && age in 0 until CACHE_TTL_MS) {
                Log.d(TAG, "TTL hit: reuse snapshot fetchedAt=${lastOk.fetchedAtMillis} age=$age ms")
                return FetchStatus.OK
            }
        }

        // 2) 离线预检
        if (!isOnline()) {
            persistAndReturnStatus(
                config, fromMillis, toMillis,
                FetchedBundle.failed(FetchStatus.NETWORK, "当前设备离线，请先连接网络。"),
            )
            return FetchStatus.NETWORK
        }

        // 3) 真实抓取
        val bundle = fetcher.fetch(
            apiKey = apiKey,
            baseUrl = config.baseUrl,
            fromMillis = fromMillis,
            toMillis = toMillis,
        )
        persistAndReturnStatus(config, fromMillis, toMillis, bundle)
        return bundle.status
    }

    private suspend fun persistAndReturnStatus(
        config: ModelConfig,
        fromMillis: Long,
        toMillis: Long,
        bundle: FetchedBundle,
        source: String = com.llmhub.app.data.model.RemoteUsageSnapshot.SOURCE_API,
        note: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val dayBucket = BillingDateUtils.todayBucketLocal()
        val snapshot = RemoteUsageSnapshot(
            apiKeyRefId = config.id,
            rangeStartMillis = fromMillis,
            rangeEndMillis = toMillis,
            fetchedAtDayBucket = dayBucket,
            fetchedAtMillis = now,
            status = bundle.status.wire,
            errorMessage = bundle.errorMessage.truncateErr(),
            totalRequests = bundle.totalRequests,
            totalInputTokens = bundle.totalInputTokens,
            totalOutputTokens = bundle.totalOutputTokens,
            totalCostAmount = bundle.totalCostAmount,
            totalCostCurrency = bundle.totalCostCurrency,
            source = source,
            note = note,
        )
        val points: List<RemoteDailyPoint> = bundle.dailyPoints.map { rdp ->
            toRemoteDailyPoint(rdp)
        }
        dao.replaceSnapshotOfDay(
            oldDayBucket = dayBucket,
            apiKeyRefId = config.id,
            snapshot = snapshot,
            points = points,
            source = source,
            note = note,
        )
    }

    private fun toRemoteDailyPoint(raw: RawDailyPoint): RemoteDailyPoint {
        // snapshotId 先填 0；Repository 的事务写入会用刚 upsert 的 id 覆盖
        return RemoteDailyPoint(
            snapshotId = 0L,
            modelId = normalizeModelId(raw.rawModelId),
            dateBucket = raw.dateBucket,
            requests = raw.requests,
            inputTokens = raw.inputTokens,
            outputTokens = raw.outputTokens,
            costAmount = raw.costAmount,
            costCurrency = raw.costCurrency,
        )
    }

    // ------------------------------------------------------------------
    // 清理
    // ------------------------------------------------------------------

    /** 删除早于 (now - keepDaysMs) 的旧快照（FK CASCADE 自动删关联 daily 点）。 */
    suspend fun purgeOldSnapshots(keepDaysMs: Long) = withContext(Dispatchers.IO) {
        val threshold = System.currentTimeMillis() - keepDaysMs
        val before = Runtime.getRuntime().freeMemory()
        dao.deleteSnapshotsOlderThan(threshold)
        Log.d(TAG, "purgeOldSnapshots: threshold=$threshold, afterFree=${Runtime.getRuntime().freeMemory() - before}")
    }

    // ------------------------------------------------------------------
    // 内网
    // ------------------------------------------------------------------

    private fun isOnline(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val TAG = "RemoteUsageRepo"
    }
}

/**
 * 输出给 UI 的远程用量聚合视图（右栏）。
 *
 * ComparePanel 侧消费：把 requests / tokens / cost 展示为一行，status 用于右上角错误小气泡，
 * isLoading 用于禁用「立即同步」按钮并显示顶部 LinearProgressIndicator。
 */
data class RemoteCompareView(
    val requests: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val costAmount: Double,
    val costCurrency: String,
    val billingKind: PlatformBillingKind,
    val fetchedAtMillis: Long,
    val status: String?,  // wire 字符串，或 null=还未抓过
    val errorMessage: String?,
    val isLoading: Boolean,
    /** 数据来源：`null`（还未写过）/ `API`（默认） / `WEB`（WebView 抽取）/ `MANUAL`（只保留参考视图）。 */
    val source: String? = null,
    /** 可选备注：通常 source=MANUAL 时包含查看时间与 URL 摘要。 */
    val note: String? = null,
) {
    companion object {
        val ZERO: RemoteCompareView = RemoteCompareView(
            requests = 0L,
            inputTokens = 0L,
            outputTokens = 0L,
            costAmount = 0.0,
            costCurrency = "CNY",
            billingKind = PlatformBillingKind.DISABLED,
            fetchedAtMillis = 0L,
            status = null,
            errorMessage = null,
            isLoading = false,
            source = null,
            note = null,
        )
    }
}
