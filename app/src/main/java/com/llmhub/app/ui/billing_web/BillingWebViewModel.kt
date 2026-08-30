package com.llmhub.app.ui.billing_web

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llmhub.app.data.billing.BillingDateUtils
import com.llmhub.app.data.billing.FetchStatus
import com.llmhub.app.data.billing.FetchedBundle
import com.llmhub.app.data.billing.truncateErr
import com.llmhub.app.data.billing.web.WebCookieManager
import com.llmhub.app.data.billing.web.WebExtractOrchestrator
import com.llmhub.app.data.model.ModelConfig
import com.llmhub.app.data.model.PlatformBillingKind
import com.llmhub.app.data.model.supportsWebLogin
import com.llmhub.app.data.model.webLoginStartUrl
import com.llmhub.app.data.model.webPostLoginIndicatorPrefix
import com.llmhub.app.data.model.webUsageUrl
import com.llmhub.app.data.repository.ModelConfigRepository
import com.llmhub.app.data.repository.RemoteUsageRepository
import com.llmhub.app.ui.stats.TimeRange
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import javax.inject.Inject
import android.content.Context
import android.webkit.WebView

enum class ExtractUiState { Idle, Running, Succeeded, Failed }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BillingWebViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val modelConfigRepository: ModelConfigRepository,
    private val remoteUsageRepository: RemoteUsageRepository,
    private val orchestrator: WebExtractOrchestrator,
    @ApplicationContext private val appCtx: Context,
) : ViewModel() {
    private val _explicitConfigId = MutableStateFlow<Long?>(null)
    private val _explicitRange = MutableStateFlow<TimeRange?>(null)
    private val _currentUrl = MutableStateFlow<String?>(null)
    val currentUrl: StateFlow<String?> = _currentUrl
    private val _loadProgress = MutableStateFlow(0)
    val loadProgress: StateFlow<Int> = _loadProgress
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    private val _extractState = MutableStateFlow(ExtractUiState.Idle)
    val extractState: StateFlow<ExtractUiState> = _extractState
    private val _extractMessage = MutableStateFlow<String?>(null)
    val extractMessage: StateFlow<String?> = _extractMessage
    private val _requestDismiss = MutableStateFlow(false)
    val requestDismiss: StateFlow<Boolean> = _requestDismiss
    private val configIdFlow: StateFlow<Long?> = combine(_explicitConfigId, flowOf(savedStateHandle.get<Long?>(KEY_MODEL_ID))) { explicit, nav -> explicit ?: nav }.stateIn(viewModelScope, SharingStarted.Eagerly, _explicitConfigId.value)
    private val rangeFlow: StateFlow<TimeRange> = combine(_explicitRange, flowOf(savedStateHandle.get<String?>(KEY_RANGE)?.let { runCatching { TimeRange.valueOf(it) }.getOrNull() } ?: TimeRange.DAYS_7)) { explicit, nav -> explicit ?: nav }.stateIn(viewModelScope, SharingStarted.Eagerly, _explicitRange.value ?: TimeRange.DAYS_7)
    val uiState: StateFlow<BillingWebUiState> = configIdFlow.flatMapLatest { id ->
        if (id == null) return@flatMapLatest flowOf<BillingWebUiState>(BillingWebUiState.Loading)
        modelConfigRepository.observeById(id).combine(rangeFlow) { config: ModelConfig?, rng: TimeRange -> Pair(config, rng) }.flatMapLatest { (cfg: ModelConfig?, rng: TimeRange) ->
            val config = cfg ?: return@flatMapLatest flowOf<BillingWebUiState>(BillingWebUiState.ConfigNotFound)
            if (!config.billingEndpointKind.supportsWebLogin) flowOf<BillingWebUiState>(BillingWebUiState.Ready(config = config, range = rng, startUrl = null, usageUrl = null, postLoginPrefix = null, errorHeader = "当前平台（${config.billingEndpointKind.displayName}）暂未提供网页登录对账。"))
            else {
                val startUrl = config.billingEndpointKind.webLoginStartUrl(config.baseUrl)
                val usageUrl = config.billingEndpointKind.webUsageUrl(config.baseUrl, rng)
                val postLoginPrefix = config.billingEndpointKind.webPostLoginIndicatorPrefix(config.baseUrl)
                flowOf<BillingWebUiState>(BillingWebUiState.Ready(config = config, range = rng, startUrl = startUrl, usageUrl = usageUrl, postLoginPrefix = postLoginPrefix, errorHeader = if (startUrl == null) missingStartUrlReason(config) else null))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BillingWebUiState.Loading)
    fun bind(configId: Long, range: TimeRange) { _explicitConfigId.value = configId; _explicitRange.value = range; _requestDismiss.value = false }
    fun setCurrentUrl(url: String?) { _currentUrl.value = url }
    fun onPageStarted() { _isLoading.value = true }
    fun onPageFinished(url: String?) { _isLoading.value = false; _loadProgress.value = 100; if (url != null) _currentUrl.value = url }
    fun onProgressChanged(progress: Int) { _loadProgress.value = progress.coerceIn(0, 100) }
    fun consumeExtractMessage() { _extractMessage.value = null }
    fun onRequestDismissHandled() { _requestDismiss.value = false }
    fun onFinishClick() { _requestDismiss.value = true }
    fun clearCookies(hostOverride: String? = null) {
        val host = hostOverride ?: _currentUrl.value?.let { runCatching { URI(it).host }.getOrNull() }.orEmpty()
        viewModelScope.launch(Dispatchers.Main) { WebCookieManager.clearCookies(host?.takeIf { it.isNotBlank() }); _extractMessage.value = "Cookie 已清理${if (host.isNullOrBlank()) "（全部）" else "（$host）"}。下次进入请重新登录。" }
    }
    fun onExtractClicked(webView: WebView) {
        val ready = (uiState.value as? BillingWebUiState.Ready) ?: run { _extractState.value = ExtractUiState.Failed; _extractMessage.value = "当前还没有有效的模型配置可关联。"; return }
        if (_extractState.value == ExtractUiState.Running) return
        _extractState.value = ExtractUiState.Running; _extractMessage.value = null
        val (from, to) = BillingDateUtils.rangeToMillis(ready.range)
        val normKeys = normalizedKeySetOf(ready.config)
        viewModelScope.launch(Dispatchers.Main) {
            val bundle: FetchedBundle = withContext(Dispatchers.Default) { runCatching { orchestrator.extract(kind = ready.config.billingEndpointKind, webView = webView, range = ready.range, baseUrlOverride = ready.config.baseUrl, normalizedModelKeySet = normKeys) }.getOrElse { t -> FetchedBundle.failed(FetchStatus.PARSE_ERR, "抽取脚本异常：${t.message ?: t.javaClass.simpleName}") } }
            runCatching { if (bundle.status == FetchStatus.OK) remoteUsageRepository.ingestWebBundle(ready.config, from, to, bundle) else remoteUsageRepository.markManualSnapshotReference(ready.config, from, to, note = "抽取失败：${bundle.status.wire} — ${(bundle.errorMessage ?: "未知错误").truncateErr() ?: ""}".trim()) }
            if (bundle.status == FetchStatus.OK) { _extractState.value = ExtractUiState.Succeeded; val cost = bundle.totalCostAmount; val tok = bundle.totalInputTokens + bundle.totalOutputTokens; _extractMessage.value = "已同步：费用 ${String.format("%.2f", cost)} ${bundle.totalCostCurrency}，Tokens $tok。" }
            else { _extractState.value = ExtractUiState.Failed; _extractMessage.value = bundle.errorMessage ?: "未抽取到可用数据。请确认页面是否已登录并停在用量/账单页，或点「保留参考视图」肉眼对账。" }
        }
    }
    fun onKeepReferenceClick() {
        val ready = (uiState.value as? BillingWebUiState.Ready) ?: return
        if (_extractState.value == ExtractUiState.Running) return
        val (from, to) = BillingDateUtils.rangeToMillis(ready.range)
        val note = "用户于 ${BillingDateUtils.formatNowLocal()} 查看网页：${(_currentUrl.value ?: ready.startUrl).orEmpty()}".take(512)
        viewModelScope.launch { runCatching { remoteUsageRepository.markManualSnapshotReference(ready.config, from, to, note) }.onSuccess { _extractState.value = ExtractUiState.Succeeded; _extractMessage.value = "已记录本次网页对账参考。" }.onFailure { e -> _extractState.value = ExtractUiState.Failed; _extractMessage.value = "记录参考视图失败：${e.message}" } }
    }
    private fun normalizedKeySetOf(config: ModelConfig): Set<String> {
        val primary = com.llmhub.app.data.billing.normalizeModelId(config.modelId)
        val aliasFromName = com.llmhub.app.data.billing.normalizeModelId(config.name)
        return buildSet { add(primary); add(aliasFromName); add(primary.substringBefore('-')); add(primary.substringBeforeLast('-')) }
    }
    private fun missingStartUrlReason(config: ModelConfig): String? = when (config.billingEndpointKind) { PlatformBillingKind.ONE_API -> "One API 中转需要先在模型配置里填写 Base URL（后台首页地址）。"; PlatformBillingKind.DEEPSEEK -> null; else -> null }
    companion object { const val KEY_MODEL_ID = "modelId"; const val KEY_RANGE = "range" }
}
sealed interface BillingWebUiState {
    data object Loading : BillingWebUiState
    data object ConfigNotFound : BillingWebUiState
    data class Ready(val config: ModelConfig, val range: TimeRange, val startUrl: String?, val usageUrl: String?, val postLoginPrefix: String?, val errorHeader: String? = null) : BillingWebUiState
}
// 软件：TRAE AI IDE | 大模型签名：Seedance 助手