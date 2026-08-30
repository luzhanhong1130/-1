package com.llmhub.app.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.llmhub.app.R
import com.llmhub.app.data.model.ApiKeyConfig
import com.llmhub.app.data.model.ModelConfig
import com.llmhub.app.data.model.ModelProvider
import com.llmhub.app.data.model.PlatformBillingKind
import com.llmhub.app.data.model.PriceCatalog
import com.llmhub.app.data.model.supportsWebLogin
import com.llmhub.app.ui.components.EmptyState
import com.llmhub.app.ui.components.LLMHubCard
import com.llmhub.app.ui.keys.ProviderDropdownField
import com.llmhub.app.ui.stats.TimeRange
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelConfigScreen(viewModel: ModelConfigViewModel = hiltViewModel(), onNavigateToBillingWeb: (Long, TimeRange) -> Unit = { _, _ -> }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ModelConfig?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.models_title)) }) }, floatingActionButton = { FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.models_add)) } }) { padding ->
        if (state.configs.isEmpty()) EmptyState(title = stringResource(R.string.models_empty_title), subtitle = stringResource(R.string.models_empty_subtitle), modifier = Modifier.padding(padding))
        else LazyColumn(modifier = Modifier.fillMaxWidth().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.configs, key = { it.id }) { c ->
                val keyName = state.keys.firstOrNull { it.id == c.apiKeyRefId }?.name ?: "—"
                ModelConfigCard(config = c, keyName = keyName, onSetDefault = { viewModel.setDefault(c.id) }, onEdit = { editing = c }, onDelete = { viewModel.delete(c) }, onBillingWebClick = if (c.billingEndpointKind.supportsWebLogin) { { onNavigateToBillingWeb(c.id, TimeRange.DAYS_7) } } else null)
            }
        }
    }
    if (showAdd || editing != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showAdd = false; editing = null }, sheetState = sheetState) {
            ModelEditor(existing = editing, keys = state.keys, onDismiss = { showAdd = false; editing = null }, onSave = { config, oldCfg -> viewModel.save(config, oldCfg); showAdd = false; editing = null })
        }
    }
}
@Composable
private fun ModelConfigCard(config: ModelConfig, keyName: String, onSetDefault: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onBillingWebClick: (() -> Unit)? = null) {
    LLMHubCard(onClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSetDefault) { Icon(if (config.isDefault) Icons.Outlined.Star else Icons.Outlined.StarBorder, contentDescription = stringResource(R.string.models_set_default), tint = if (config.isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(text = config.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); if (config.isDefault) Text(text = " " + stringResource(R.string.models_default_badge), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                Text(text = "${config.provider.displayName} · ${config.modelId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = stringResource(R.string.models_card_meta, keyName, config.priceInputPer1k, config.priceOutputPer1k), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "${stringResource(R.string.models_field_billing_kind)}：${config.billingEndpointKind.displayName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (onBillingWebClick != null) AssistChip(onClick = onBillingWebClick, label = { Text(stringResource(R.string.stats_web_card_trail)) }, leadingIcon = { Icon(Icons.Outlined.Public, null) })
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete)) }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelEditor(existing: ModelConfig?, keys: List<ApiKeyConfig>, onDismiss: () -> Unit, onSave: (ModelConfig, ModelConfig?) -> Unit) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var provider by remember(existing?.id) { mutableStateOf(existing?.provider ?: ModelProvider.OPENAI) }
    var modelId by remember(existing?.id) { mutableStateOf(existing?.modelId ?: "") }
    var baseUrl by remember(existing?.id) { mutableStateOf(existing?.baseUrl ?: provider.defaultBaseUrl) }
    var apiKeyRefId by remember(existing?.id) { mutableStateOf(existing?.apiKeyRefId ?: keys.firstOrNull()?.id ?: 0L) }
    var temperature by remember(existing?.id) { mutableStateOf((existing?.temperature ?: 0.7f).toString()) }
    var maxTokens by remember(existing?.id) { mutableStateOf((existing?.maxTokens ?: 2048).toString()) }
    var priceIn by remember(existing?.id) { mutableStateOf(existing?.priceInputPer1k.toString()) }
    var priceOut by remember(existing?.id) { mutableStateOf(existing?.priceOutputPer1k.toString()) }
    var billingKind by remember(existing?.id) { mutableStateOf(existing?.billingEndpointKind ?: provider.defaultBillingKind()) }
    var billingKindTouched by remember(existing?.id) { mutableStateOf(existing != null) }
    var attemptedSave by remember { mutableStateOf(false) }
    val onProviderChanged: (ModelProvider) -> Unit = { p -> provider = p; baseUrl = p.defaultBaseUrl; if (!billingKindTouched) billingKind = p.defaultBillingKind() }
    val onResetPrices: () -> Unit = { val (inP, outP) = PriceCatalog.defaultPricesFor(provider, modelId); priceIn = inP.toString(); priceOut = outP.toString() }
    val nameErr = attemptedSave && name.isBlank()
    val modelIdErr = attemptedSave && modelId.isBlank()
    val apiKeyErr = attemptedSave && apiKeyRefId == 0L && keys.isNotEmpty()
    val tempValue = temperature.toFloatOrNull(); val tempErr = attemptedSave && (tempValue == null || tempValue < 0f || tempValue > 2f)
    val maxTokensValue = maxTokens.toIntOrNull(); val maxTokensErr = attemptedSave && (maxTokensValue == null || maxTokensValue < 1 || maxTokensValue > 32000)
    val priceInValue = priceIn.toDoubleOrNull(); val priceOutValue = priceOut.toDoubleOrNull()
    val priceInErr = attemptedSave && (priceInValue == null || priceInValue < 0.0); val priceOutErr = attemptedSave && (priceOutValue == null || priceOutValue < 0.0)
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = stringResource(if (existing == null) R.string.models_add else R.string.models_edit), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.models_field_name)) }, singleLine = true, isError = nameErr, supportingText = if (nameErr) { { Text(stringResource(R.string.models_err_name_required)) } } else null, modifier = Modifier.fillMaxWidth())
        ProviderDropdownField(selected = provider, onSelected = onProviderChanged, label = stringResource(R.string.models_field_provider))
        OutlinedTextField(value = modelId, onValueChange = { modelId = it }, label = { Text(stringResource(R.string.models_field_model_id)) }, singleLine = true, isError = modelIdErr, supportingText = if (modelIdErr) { { Text(stringResource(R.string.models_err_model_id_required)) } } else null, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text(stringResource(R.string.models_field_base_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (keys.isEmpty()) Text(text = stringResource(R.string.models_no_keys_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else {
            var expanded by remember { mutableStateOf(false) }
            val selectedKey = keys.firstOrNull { it.id == apiKeyRefId }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(value = selectedKey?.name ?: "", onValueChange = {}, readOnly = true, isError = apiKeyErr, label = { Text(stringResource(R.string.models_field_api_key_ref)) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, supportingText = if (apiKeyErr) { { Text(stringResource(R.string.models_err_api_key_required)) } } else null, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true))
                androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { keys.forEach { k -> DropdownMenuItem(text = { Text("${k.name} (${k.provider.displayName})") }, onClick = { apiKeyRefId = k.id; expanded = false }) } }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = temperature, onValueChange = { temperature = it }, label = { Text(stringResource(R.string.models_field_temperature)) }, singleLine = true, isError = tempErr, supportingText = if (tempErr) { { Text(stringResource(R.string.models_err_temperature_range)) } } else null, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(value = maxTokens, onValueChange = { maxTokens = it }, label = { Text(stringResource(R.string.models_field_max_tokens)) }, singleLine = true, isError = maxTokensErr, supportingText = if (maxTokensErr) { { Text(stringResource(R.string.models_err_max_tokens_range)) } } else null, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = priceIn, onValueChange = { priceIn = it }, label = { Text(stringResource(R.string.models_field_price_input)) }, singleLine = true, isError = priceInErr, supportingText = if (priceInErr) { { Text(stringResource(R.string.models_err_price_range)) } } else null, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(value = priceOut, onValueChange = { priceOut = it }, label = { Text(stringResource(R.string.models_field_price_output)) }, singleLine = true, isError = priceOutErr, supportingText = if (priceOutErr) { { Text(stringResource(R.string.models_err_price_range)) } } else null, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { OutlinedButtonWithIcon(onClick = onResetPrices, icon = { Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)) }, text = { Text(stringResource(R.string.models_action_reset_price)) }) }
        BillingKindDropdown(selected = billingKind, onSelected = { billingKind = it; billingKindTouched = true }, label = stringResource(R.string.models_field_billing_kind))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            Spacer(Modifier.size(8.dp))
            Button(onClick = { attemptedSave = true; val config = ModelConfig(id = existing?.id ?: 0, name = name.trim(), provider = provider, modelId = modelId.trim(), baseUrl = baseUrl.trim().ifBlank { provider.defaultBaseUrl }, apiKeyRefId = apiKeyRefId, temperature = tempValue ?: 0.7f, maxTokens = maxTokensValue ?: 2048, priceInputPer1k = priceInValue ?: 0.0, priceOutputPer1k = priceOutValue ?: 0.0, isDefault = existing?.isDefault ?: false, billingEndpointKind = billingKind); val valid = config.name.isNotBlank() && config.modelId.isNotBlank() && (apiKeyRefId != 0L || keys.isEmpty()) && tempValue != null && tempValue in 0f..2f && maxTokensValue != null && maxTokensValue in 1..32000 && priceInValue != null && priceInValue >= 0.0 && priceOutValue != null && priceOutValue >= 0.0; if (valid) onSave(config, existing) }, enabled = name.isNotBlank() && modelId.isNotBlank() && (apiKeyRefId != 0L || keys.isEmpty())) { Text(stringResource(R.string.action_save)) }
        }
        Spacer(Modifier.size(32.dp))
    }
}
@Composable
private fun OutlinedButtonWithIcon(onClick: () -> Unit, icon: @Composable () -> Unit, text: @Composable () -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = onClick) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { icon(); text() } }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BillingKindDropdown(selected: PlatformBillingKind, onSelected: (PlatformBillingKind) -> Unit, label: String) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(value = selected.displayName, onValueChange = {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true))
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { PlatformBillingKind.selectable.forEach { kind -> DropdownMenuItem(text = { Column { Text(kind.displayName); Text(text = billingKindSubtitle(kind), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, onClick = { onSelected(kind); expanded = false }) } }
    }
}
private fun billingKindSubtitle(kind: PlatformBillingKind): String = when (kind) {
    PlatformBillingKind.DISABLED -> "不从后台拉取用量，仅统计本地记录"
    PlatformBillingKind.ONE_API -> "One API / New API 类中转平台 /dashboard/billing 与用量 API"
    PlatformBillingKind.OPENAI_OFFICIAL -> "OpenAI 官方 /v1/usage 接口（需对应 Key 权限）"
    PlatformBillingKind.DEEPSEEK -> "DeepSeek 官方用量 API（api.deepseek.com）"
    PlatformBillingKind.DASHSCOPE -> "DashScope 通义千问后台用量查询"
}
// 软件：TRAE AI IDE | 大模型签名：Seedance 助手