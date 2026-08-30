package com.llmhub.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.llmhub.app.R
import com.llmhub.app.data.model.ChatMessage
import com.llmhub.app.data.model.ChatRole
import com.llmhub.app.data.model.ModelConfig
import com.llmhub.app.ui.components.EmptyState
import com.llmhub.app.ui.components.LLMHubCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val models by viewModel.allModels.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf("") }
    var showModelSheet by remember { mutableStateOf(false) }
    var showSessionSheet by remember { mutableStateOf(false) }
    var renamingSessionId by remember { mutableStateOf<String?>(null) }
    var deletingSessionId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }
    val currentModel = uiState.currentModelConfig
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
    LaunchedEffect(uiState.errorMessage) {
        val msg = uiState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
        viewModel.clearError()
    }
    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showSessionSheet = true }) {
                    Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.chat_new_session))
                }
                Surface(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), onClick = { showModelSheet = true }) {
                    Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = currentModel?.name ?: stringResource(R.string.chat_select_model), style = MaterialTheme.typography.titleMedium, color = if (currentModel == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.size(8.dp))
                IconButton(onClick = { viewModel.newSession() }) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.chat_new_session)) }
            }
            if (messages.isEmpty() && !uiState.isGenerating) {
                EmptyState(title = stringResource(R.string.chat_empty_title), subtitle = if (currentModel == null) stringResource(R.string.chat_no_model_hint) else stringResource(R.string.chat_empty_subtitle))
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(messages, key = { it.id }) { msg -> MessageBubble(msg) }
                    if (uiState.isGenerating && messages.lastOrNull()?.isStreaming == true) item { GeneratingDots() }
                }
            }
            InputBar(text = inputText, onTextChange = { inputText = it }, onSend = { if (currentModel != null && inputText.isNotBlank()) { viewModel.send(inputText); inputText = ""; keyboard?.hide() } }, enabled = currentModel != null && !uiState.isGenerating, isGenerating = uiState.isGenerating)
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
    if (showModelSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showModelSheet = false }, sheetState = sheetState) {
            Text(text = stringResource(R.string.chat_select_model), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            if (models.isEmpty()) Text(stringResource(R.string.chat_no_model_dropdown), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(models, key = { it.id }) { m ->
                    Surface(onClick = { viewModel.selectModel(m); showModelSheet = false }, color = if (m.id == currentModel?.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                            Text(m.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${m.provider.displayName} · ${m.modelId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (showSessionSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showSessionSheet = false }, sheetState = sheetState) {
            Text(text = stringResource(R.string.chat_history_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            if (sessions.isEmpty()) Text(stringResource(R.string.chat_no_sessions), modifier = Modifier.padding(24.dp))
            else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(sessions, key = { it.id }) { s ->
                    Surface(onClick = { viewModel.selectSession(s.id); showSessionSheet = false }, color = if (s.id == uiState.currentSessionId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f).padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                                Text(s.title, style = MaterialTheme.typography.bodyLarge)
                                Text(text = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(s.updatedAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { renamingSessionId = s.id }) { Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_edit)) }
                            IconButton(onClick = { deletingSessionId = s.id }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete)) }
                        }
                    }
                }
            }
        }
    }
    val renamingId = renamingSessionId
    if (renamingId != null) {
        val session = sessions.firstOrNull { it.id == renamingId }
        var renameInput by remember(renamingId) { mutableStateOf(session?.title ?: "") }
        AlertDialog(onDismissRequest = { renamingSessionId = null }, title = { Text(stringResource(R.string.chat_rename_title)) }, text = { OutlinedTextField(value = renameInput, onValueChange = { renameInput = it }, label = { Text(stringResource(R.string.chat_rename_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }, confirmButton = { TextButton(onClick = { if (renameInput.isNotBlank()) viewModel.renameSession(renamingId, renameInput); renamingSessionId = null }) { Text(stringResource(R.string.action_confirm)) } }, dismissButton = { TextButton(onClick = { renamingSessionId = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
    val deletingId = deletingSessionId
    if (deletingId != null) {
        AlertDialog(onDismissRequest = { deletingSessionId = null }, title = { Text(stringResource(R.string.chat_delete_title)) }, text = { Text(stringResource(R.string.chat_delete_message)) }, confirmButton = { TextButton(onClick = { viewModel.deleteSession(deletingId); deletingSessionId = null }) { Text(stringResource(R.string.action_delete)) } }, dismissButton = { TextButton(onClick = { deletingSessionId = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
}
@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (isUser) 16.dp else 4.dp, bottomEnd = if (isUser) 4.dp else 16.dp), modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(text = message.content.ifBlank { if (message.isStreaming) "…" else "" }, style = MaterialTheme.typography.bodyMedium, color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                if (message.error != null) { Spacer(Modifier.height(4.dp)); Text(text = "⚠ ${message.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
@Composable
private fun GeneratingDots() {
    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.chat_generating), style = MaterialTheme.typography.bodySmall)
    }
}
@Composable
private fun InputBar(text: String, onTextChange: (String) -> Unit, onSend: () -> Unit, enabled: Boolean, isGenerating: Boolean) {
    Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(value = text, onValueChange = onTextChange, modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 140.dp), placeholder = { Text(stringResource(R.string.chat_input_hint)) }, shape = RoundedCornerShape(20.dp), enabled = enabled, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { onSend() }), maxLines = 6)
            Spacer(Modifier.size(8.dp))
            IconButton(onClick = onSend, enabled = enabled && text.isNotBlank(), modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary).size(48.dp)) {
                Icon(imageVector = if (isGenerating) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send, contentDescription = if (isGenerating) stringResource(R.string.chat_stop) else stringResource(R.string.chat_send), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
// 软件：TRAE AI IDE | 大模型签名：Seedance 助手