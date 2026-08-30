package com.llmhub.app.ui.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llmhub.app.data.model.ApiKeyConfig
import com.llmhub.app.data.model.ModelProvider
import com.llmhub.app.data.repository.ApiKeyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ApiKeyViewModel @Inject constructor(
    private val apiKeyRepository: ApiKeyRepository,
) : ViewModel() {

    val keys: StateFlow<List<ApiKeyConfig>> = apiKeyRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun add(name: String, provider: ModelProvider, value: String) {
        if (value.isBlank()) return
        viewModelScope.launch { apiKeyRepository.add(name, provider, value) }
    }

    fun update(config: ApiKeyConfig, newName: String, newValue: String?) {
        viewModelScope.launch {
            apiKeyRepository.update(config.copy(name = newName), newValue)
        }
    }

    fun delete(config: ApiKeyConfig) {
        viewModelScope.launch { apiKeyRepository.delete(config) }
    }

    /** 解密读取（IO 线程，避免主线程 ANR）。 */
    suspend fun decryptedValue(id: Long): String? = apiKeyRepository.loadDecrypted(id)
}
// 软件：TRAE AI IDE | 大模型签名：Seedance 助手