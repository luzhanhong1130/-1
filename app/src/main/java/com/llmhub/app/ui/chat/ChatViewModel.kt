package com.llmhub.app.ui.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llmhub.app.R
import com.llmhub.app.data.model.ChatMessage
import com.llmhub.app.data.model.ChatSession
import com.llmhub.app.data.model.ModelConfig
import com.llmhub.app.data.remote.ChatStreamEvent
import com.llmhub.app.data.repository.ChatRepository
import com.llmhub.app.data.repository.ModelConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val currentSessionId: String? = null,
    val currentModelConfig: ModelConfig? = null,
    val isGenerating: Boolean = false,
    val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val modelConfigRepository: ModelConfigRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    val sessions: StateFlow<List<ChatSession>> = chatRepository.observeSessions()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val messages: StateFlow<List<ChatMessage>> = _uiState
        .flatMapLatest { state -> state.currentSessionId?.let { chatRepository.observeMessages(it) } ?: emptyFlow() }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allModels: StateFlow<List<ModelConfig>> = modelConfigRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    init {
        viewModelScope.launch {
            modelConfigRepository.ensureDefault()
            val default = modelConfigRepository.getDefault()
            _uiState.update { it.copy(currentModelConfig = default) }
            val current = chatRepository.observeSessions().first()
            val sessionId = current.firstOrNull()?.id ?: chatRepository.createSession(default?.id)
            _uiState.update { it.copy(currentSessionId = sessionId) }
        }
    }
    fun selectSession(sessionId: String) {
        viewModelScope.launch {
            val session = chatRepository.getSession(sessionId) ?: return@launch
            val model = session.lastModelConfigId?.let { chatRepository.getModelConfig(it) }
            _uiState.update { it.copy(currentSessionId = sessionId, currentModelConfig = model ?: it.currentModelConfig) }
        }
    }
    fun selectModel(modelConfig: ModelConfig) { _uiState.update { it.copy(currentModelConfig = modelConfig) } }
    fun newSession() {
        viewModelScope.launch {
            val model = _uiState.value.currentModelConfig
            val sessionId = chatRepository.createSession(model?.id)
            _uiState.update { it.copy(currentSessionId = sessionId) }
        }
    }
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            chatRepository.deleteSession(sessionId)
            if (_uiState.value.currentSessionId == sessionId) {
                val remaining = chatRepository.observeSessions().first()
                _uiState.update { it.copy(currentSessionId = remaining.firstOrNull()?.id) }
            }
        }
    }
    fun renameSession(sessionId: String, title: String) {
        viewModelScope.launch { chatRepository.renameSession(sessionId, title.trim()) }
    }
    fun send(text: String) {
        val model = _uiState.value.currentModelConfig ?: return
        val sessionId = _uiState.value.currentSessionId ?: return
        if (text.isBlank() || _uiState.value.isGenerating) return
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, errorMessage = null) }
            chatRepository.sendUserMessageAndStream(sessionId, model, text)
                .catch { e -> _uiState.update { it.copy(isGenerating = false, errorMessage = e.message ?: context.getString(R.string.chat_error_unknown)) } }
                .collect { ev -> when (ev) {
                    is ChatStreamEvent.Error -> _uiState.update { it.copy(isGenerating = false, errorMessage = ev.message) }
                    is ChatStreamEvent.Done -> _uiState.update { it.copy(isGenerating = false, errorMessage = null) }
                    else -> Unit
                } }
        }
    }
    fun clearError() { _uiState.update { it.copy(errorMessage = null) } }
}
// 软件：TRAE AI IDE | 大模型签名：Seedance 助手