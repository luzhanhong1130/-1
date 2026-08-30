package com.llmhub.app.ui.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.llmhub.app.R
import com.llmhub.app.data.model.ApiKeyConfig
import com.llmhub.app.data.model.ModelProvider
import com.llmhub.app.ui.components.EmptyState
import com.llmhub.app.ui.components.LLMHubCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeyScreen(viewModel: ApiKeyViewModel = hiltViewModel()) {
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ApiKeyConfig?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var revealedIds by remember { mutableStateOf(setOf<Long>()) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val masked = stringResource(R.string.keys_masked)
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.keys_title)) }) }, floatingActionButton = { FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.keys_add)) } }) { padding ->
        if (keys.isEmpty()) EmptyState(title = stringResource(R.string.keys_empty_title), subtitle = stringResource(R.string.keys_empty_subtitle), modifier = Modifier.padding(padding))
        else LazyColumn(modifier = Modifier.fillMaxWidth().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(keys, key = { it.id }) { key ->
                val revealed = key.id in revealedIds
                val decrypted by produceState<String?>(initialValue = null, key.id, revealed) { if (revealed) value = viewModel.decryptedValue(key.id) }
                val displayValue = if (revealed) (decrypted ?: "—") else masked
                KeyCard(key = key, displayValue = displayValue, revealed = revealed, onToggleReveal = { revealedIds = if (revealed) revealedIds - key.id else revealedIds + key.id }, onCopy = { scope.launch { viewModel.decryptedValue(key.id)?.let { clipboard.setText(AnnotatedString(it)) } } }, onEdit = { editing = key }, onDelete = { viewModel.delete(key) })
            }
        }
    }
    if (showAdd || editing != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showAdd = false; editing = null }, sheetState = sheetState) {
            KeyEditor(existing = editing, onDismiss = { showAdd = false; editing = null }, onSave = { name, provider, value -> val e = editing; if (e != null) viewModel.update(e, name, value) else if (value != null) viewModel.add(name, provider, value); showAdd = false; editing = null })
        }
    }
}
@Composable
private fun KeyCard(key: ApiKeyConfig, displayValue: String, revealed: Boolean, onToggleReveal: () -> Unit, onCopy: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    LLMHubCard(onClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = key.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(text = key.provider.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(text = displayValue, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggleReveal) { Icon(if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (revealed) stringResource(R.string.keys_hide) else stringResource(R.string.keys_show)) }
            IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.action_copy)) }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete)) }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyEditor(existing: ApiKeyConfig?, onDismiss: () -> Unit, onSave: (name: String, ModelProvider, value: String?) -> Unit) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var provider by remember(existing?.id) { mutableStateOf(existing?.provider ?: ModelProvider.OPENAI) }
    var value by remember(existing?.id) { mutableStateOf("") }
    var showValue by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = stringResource(if (existing == null) R.string.keys_add else R.string.keys_edit), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.keys_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ProviderDropdownField(selected = provider, onSelected = { provider = it }, label = stringResource(R.string.keys_field_provider))
        OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(stringResource(if (existing == null) R.string.keys_field_value else R.string.keys_field_value_new)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = if (showValue) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton(onClick = { showValue = !showValue }) { Icon(if (showValue) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null) } })
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            Spacer(Modifier.size(8.dp))
            Button(onClick = { val finalValue: String? = when { existing != null && value.isBlank() -> null else -> value }; val canSave = name.isNotBlank() && (existing != null || finalValue != null); if (canSave) onSave(name, provider, finalValue) }, enabled = name.isNotBlank() && (existing != null || value.isNotBlank())) { Text(stringResource(R.string.action_save)) }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDropdownField(selected: ModelProvider, onSelected: (ModelProvider) -> Unit, label: String) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(value = selected.displayName, onValueChange = {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true))
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ModelProvider.selectable.forEach { p -> DropdownMenuItem(text = { Text(p.displayName) }, onClick = { onSelected(p); expanded = false }) }
        }
    }
}
// 软件：TRAE AI IDE | 大模型签名：Seedance 助手