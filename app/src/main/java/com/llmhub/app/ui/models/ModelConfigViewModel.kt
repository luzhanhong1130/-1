package com.llmhub.app.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llmhub.app.data.model.ApiKeyConfig
import com.llmhub.app.data.model.ModelConfig
import com.llmhub.app.data.repository.ApiKeyRepository
import com.llmhub.app.data.repository.ModelConfigRepository
import com.llmhub.app.data.repository.UsageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelListUiState(
    val configs: List<ModelConfig> = emptyList(),
    val keys: List<ApiKeyConfig> = emptyList(),
)

@HiltViewModel
class ModelConfigViewModel @Inject constructor(
    private val repository: ModelConfigRepository,
    private val apiKeyRepository: ApiKeyRepository,
    private val usageRepository: UsageRepository,
) : ViewModel() {

    val state: StateFlow<ModelListUiState> = combine(
        repository.observeAll(),
        apiKeyRepository.observeAll(),
    ) { configs, keys -> ModelListUiState(configs, keys) }
        .stateIn(viewModelScope, SharingStarted.Lazily, ModelListUiState())

    fun save(config: ModelConfig, oldConfig: ModelConfig? = null) {
        viewModelScope.launch {
            val savedId = repository.save(config)
            if (oldConfig != null && config.id != 0L) {
                val priceChanged =
                    oldConfig.priceInputPer1k != config.priceInputPer1k ||
                    oldConfig.priceOutputPer1k != config.priceOutputPer1k
                if (priceChanged) {
                    usageRepository.recalculateCostsForModel(
                        modelConfigId = config.id,
                        priceInputPer1k = config.priceInputPer1k,
                        priceOutputPer1k = config.priceOutputPer1k,
                    )
                }
            } else if (config.id == 0L && savedId != 0L) {
                // 新建：理论上无历史记录，无需重算；保留分支扩展。
            }
        }
    }

    fun delete(config: ModelConfig) {
        viewModelScope.launch { repository.delete(config) }
    }

    fun setDefault(id: Long) {
        viewModelScope.launch { repository.setDefault(id) }
    }

    fun recalculateCosts(config: ModelConfig) {
        viewModelScope.launch {
            usageRepository.recalculateCostsForModel(
                modelConfigId = config.id,
                priceInputPer1k = config.priceInputPer1k,
                priceOutputPer1k = config.priceOutputPer1k,
            )
        }
    }
}
// 软件：TRAE AI IDE | 大模型签名：Seedance 助手