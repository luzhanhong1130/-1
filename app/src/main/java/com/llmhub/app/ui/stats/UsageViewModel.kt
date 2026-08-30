package com.llmhub.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llmhub.app.data.billing.BillingDateUtils
import com.llmhub.app.data.billing.FetchStatus
import com.llmhub.app.data.billing.normalizeModelId
import com.llmhub.app.data.model.DailyUsagePoint
import com.llmhub.app.data.model.ModelConfig
import com.llmhub.app.data.model.ModelDetailStat
import com.llmhub.app.data.model.ModelUsageStat
import com.llmhub.app.data.model.PlatformBillingKind
import com.llmhub.app.data.model.SuccessBreakdown
import com.llmhub.app.data.model.UsageSummary
import com.llmhub.app.data.model.supportsWebLogin
import com.llmhub.app.data.prefs.RefreshMode
import com.llmhub.app.data.prefs.WebRefreshPrefs
import com.llmhub.app.data.repository.ModelConfigRepository
import com.llmhub.app.data.repository.RemoteCompareView
import com.llmhub.app.data.repository.RemoteUsageRepository
import com.llmhub.app.data.repository.UsageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

enum class TimeRange(val label: String) {
    TODAY("今日"),
    DAYS_7("近 7 天"),
    DAYS_30("近 30 天"),
    ALL("全部");
    fun fromMillis(): Long = when (this) {
        ALL -> 0L
        TODAY -> Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        DAYS_7 -> Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }.timeInMillis
        DAYS_30 -> Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -30) }.timeInMillis
    }
}
const val UNSELECTED_MODEL_ID: Long = -1L
sealed interface RemoteUiState {
    data object Idle : RemoteUiState
    data object Loading : RemoteUiState
    data class Error(val message: String) : RemoteUiState
    data class Data(val syncedAtMillis: Long) : RemoteUiState
}
data class SelectedModelMeta(
    val config: ModelConfig? = null,
    val modelName: String,
    val providerDisplayName: String,
    val billingKind: PlatformBillingKind,
    val priceInputPer1k: Double,
    val priceOutputPer1k: Double,
) {
    companion object {
        val EMPTY: SelectedModelMeta = SelectedModelMeta(null, "", "", PlatformBillingKind.DISABLED, 0.0, 0.0)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UsageViewModel @Inject constructor(
    private val usageRepository: UsageRepository,
    private val modelConfigRepository: ModelConfigRepository,
    private val remoteUsageRepository: RemoteUsageRepository,
    private val webRefreshPrefs: WebRefreshPrefs,
) : ViewModel() {
    private val _range = MutableStateFlow(TimeRange.DAYS_7)
    val range: StateFlow<TimeRange> = _range.asStateFlow()
    private val _selectedModelId = MutableStateFlow(UNSELECTED_MODEL_ID)
    val selectedModelId: StateFlow<Long> = _selectedModelId.asStateFlow()
    private val _remoteRefreshState = MutableStateFlow<RemoteUiState>(RemoteUiState.Idle)
    val remoteRefreshState: StateFlow<RemoteUiState> = _remoteRefreshState.asStateFlow()
    private val _showWebLoginDialog = MutableStateFlow<Long?>(null)
    val showWebLoginDialog: StateFlow<Long?> = _showWebLoginDialog.asStateFlow()
    fun openWebLogin(configId: Long? = null) {
        val id = configId ?: takeIf { _selectedModelId.value != UNSELECTED_MODEL_ID }?.let { _selectedModelId.value } ?: return
        _showWebLoginDialog.value = id
    }
    fun dismissWebLogin() { _showWebLoginDialog.value = null }
    private val _defaultRefreshMode = MutableStateFlow(RefreshMode.PREFER_API)
    val defaultRefreshMode: StateFlow<RefreshMode> = _defaultRefreshMode.asStateFlow()
    fun setDefaultRefreshMode(mode: RefreshMode) {
        val id = _selectedModelId.value
        if (id == UNSELECTED_MODEL_ID) return
        _defaultRefreshMode.value = mode
        viewModelScope.launch { webRefreshPrefs.setDefaultMode(id, mode) }
    }
    private fun refreshPrefModeFromDisk(id: Long) {
        if (id == UNSELECTED_MODEL_ID) { _defaultRefreshMode.value = RefreshMode.PREFER_API; return }
        val mode = runCatching { webRefreshPrefs.getDefaultMode(id) }.getOrDefault(RefreshMode.PREFER_API)
        _defaultRefreshMode.value = mode
    }
    val summary: StateFlow<UsageSummary> = _range
        .flatMapLatest { usageRepository.observeSummary(it.fromMillis()) }
        .stateIn(viewModelScope, SharingStarted.Lazily, UsageSummary.empty())
    val byModel: StateFlow<List<ModelUsageStat>> = _range
        .flatMapLatest { usageRepository.observeByModel(it.fromMillis()) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val selectedModelMeta: StateFlow<SelectedModelMeta> = _selectedModelId.flatMapLatest { id ->
        if (id == UNSELECTED_MODEL_ID) flowOf(SelectedModelMeta.EMPTY)
        else modelConfigRepository.observeById(id).map { cfg ->
            if (cfg == null) SelectedModelMeta.EMPTY else SelectedModelMeta(cfg, cfg.name, cfg.provider.displayName, cfg.billingEndpointKind, cfg.priceInputPer1k, cfg.priceOutputPer1k)
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, SelectedModelMeta.EMPTY)
    private val selectedDetailTrigger: Flow<Pair<Long, TimeRange>> = combine(_selectedModelId, _range) { id, r -> id to r }
    val selectedModelDetail: StateFlow<ModelDetailStat> = selectedDetailTrigger.flatMapLatest { (id, r) ->
        if (id == UNSELECTED_MODEL_ID) flowOf(EMPTY_DETAIL) else usageRepository.observeModelDetail(id, r.fromMillis())
    }.stateIn(viewModelScope, SharingStarted.Lazily, EMPTY_DETAIL)
    val selectedDailyPoints: StateFlow<List<DailyUsagePoint>> = selectedDetailTrigger.flatMapLatest { (id, r) ->
        if (id == UNSELECTED_MODEL_ID) emptyFlow() else usageRepository.observeDailyByModel(id, r.fromMillis())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val selectedBreakdown: StateFlow<SuccessBreakdown> = selectedDetailTrigger.flatMapLatest { (id, r) ->
        if (id == UNSELECTED_MODEL_ID) flowOf(EMPTY_BREAKDOWN) else usageRepository.observeSuccessBreakdown(id, r.fromMillis())
    }.stateIn(viewModelScope, SharingStarted.Lazily, EMPTY_BREAKDOWN)
    val remoteCompare: StateFlow<RemoteCompareView> = combine(selectedModelMeta, _range) { meta, r -> Pair(meta, r) }
        .flatMapLatest { (meta, r) ->
            val cfg = meta.config
            val (fromMs, toMs) = BillingDateUtils.rangeToMillis(r)
            val normalizedKey = cfg?.name?.takeIf { it.isNotBlank() }?.let { normalizeModelId(it) }
            remoteUsageRepository.observeCompareSide(config = cfg, fromMillis = fromMs, toMillis = toMs, normalizedModelKey = normalizedKey)
        }
        .also { flow -> flow.map { rv ->
            when {
                rv.isLoading -> RemoteUiState.Loading
                !rv.errorMessage.isNullOrBlank() -> RemoteUiState.Error(rv.errorMessage)
                rv.status == FetchStatus.NOT_SUPPORTED.wire -> RemoteUiState.Error(rv.errorMessage ?: "该平台不支持抓取，请切换 Billing 类型。")
                rv.status == FetchStatus.AUTH_FAIL.wire -> RemoteUiState.Error(rv.errorMessage ?: "API Key 鉴权失败，请重新填写。")
                rv.status == FetchStatus.RATE_LIMITED.wire -> RemoteUiState.Error(rv.errorMessage ?: "平台限流，请稍后重试。")
                rv.status == FetchStatus.NETWORK.wire -> RemoteUiState.Error(rv.errorMessage ?: "网络错误，请检查连接。")
                rv.status == FetchStatus.PARSE_ERR.wire -> RemoteUiState.Error(rv.errorMessage ?: "数据解析失败，可能平台接口已变更。")
                rv.status == FetchStatus.OK.wire && rv.fetchedAtMillis > 0L -> RemoteUiState.Data(rv.fetchedAtMillis)
                else -> RemoteUiState.Idle
            }
        }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, RemoteUiState.Idle).let { dsf -> viewModelScope.launch { dsf.collect { s -> _remoteRefreshState.value = s } } }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, RemoteCompareView.ZERO)
    fun setRange(r: TimeRange) { _range.value = r }
    fun selectModel(modelConfigId: Long?) {
        val id = modelConfigId ?: UNSELECTED_MODEL_ID
        _selectedModelId.value = id
        refreshPrefModeFromDisk(id)
    }
    fun refreshRemoteUsage() {
        val cfg = selectedModelMeta.value.config ?: run { _remoteRefreshState.value = RemoteUiState.Error("请先选中一个具体模型。"); return }
        if (cfg.billingEndpointKind == PlatformBillingKind.DISABLED) { _remoteRefreshState.value = RemoteUiState.Error("尚未启用后台抓取，请选择 Billing 类型。"); return }
        if (cfg.billingEndpointKind.supportsWebLogin && _defaultRefreshMode.value == RefreshMode.PREFER_WEB) { openWebLogin(cfg.id); return }
        viewModelScope.launch {
            _remoteRefreshState.value = RemoteUiState.Loading
            val (fromMs, toMs) = BillingDateUtils.rangeToMillis(range.value)
            val status = remoteUsageRepository.forceRefresh(config = cfg, fromMillis = fromMs, toMillis = toMs, ignoreTTL = true)
            if (status == null) {
                val latest = remoteUsageRepository.forceRefresh(config = cfg, fromMillis = fromMs, toMillis = toMs, ignoreTTL = false)
                if (latest == null) _remoteRefreshState.value = RemoteUiState.Error("刷新被抢占，请重试。")
            }
        }
    }
    companion object {
        private val EMPTY_DETAIL = ModelDetailStat(0, 0, 0, 0, 0, 0, 0.0, 0L, 0L, 0L)
        private val EMPTY_BREAKDOWN = SuccessBreakdown(0, 0)
    }
}
// 软件：TRAE AI IDE | 大模型签名：Seedance 助手