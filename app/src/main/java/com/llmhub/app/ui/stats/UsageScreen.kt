package com.llmhub.app.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.llmhub.app.R
import com.llmhub.app.data.model.ModelUsageStat
import com.llmhub.app.ui.components.EmptyState
import com.llmhub.app.ui.components.LLMHubCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(viewModel: UsageViewModel = hiltViewModel(), onNavigateToModelEdit: (Long) -> Unit = {}, onNavigateToBillingWeb: (Long, TimeRange) -> Unit = { _, _ -> }) {
    val range by viewModel.range.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val byModel by viewModel.byModel.collectAsStateWithLifecycle()
    val selectedModelId by viewModel.selectedModelId.collectAsStateWithLifecycle()
    val selectedMeta by viewModel.selectedModelMeta.collectAsStateWithLifecycle()
    val selectedDetail by viewModel.selectedModelDetail.collectAsStateWithLifecycle()
    val selectedDaily by viewModel.selectedDailyPoints.collectAsStateWithLifecycle()
    val selectedBreakdown by viewModel.selectedBreakdown.collectAsStateWithLifecycle()
    val remoteState by viewModel.remoteRefreshState.collectAsStateWithLifecycle()
    val remoteCompare by viewModel.remoteCompare.collectAsStateWithLifecycle()
    val showWebDialogId by viewModel.showWebLoginDialog.collectAsStateWithLifecycle()
    val defaultRefreshMode by viewModel.defaultRefreshMode.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val isGlobal = selectedModelId == UNSELECTED_MODEL_ID
    Scaffold(topBar = { TopAppBar(title = { Column { Text(stringResource(R.string.stats_title)); if (!isGlobal && selectedMeta.modelName.isNotEmpty()) Text(text = "${selectedMeta.modelName} · ${selectedMeta.providerDisplayName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, actions = { if (!isGlobal && selectedMeta.config != null) IconButton(onClick = { selectedMeta.config?.id?.let(onNavigateToModelEdit) }) { Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_edit)) } }) }, snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { TimeRange.entries.forEach { r -> FilterChip(selected = r == range, onClick = { viewModel.setRange(r) }, label = { Text(r.label) }) } } }
            item { ModelSelectorDropdown(modelStats = byModel, selectedModelId = selectedModelId, onSelected = { viewModel.selectModel(it) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) }
            if (isGlobal) {
                item { LLMHubCard(modifier = Modifier.padding(16.dp)) { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { StatItem(label = stringResource(R.string.stats_total_requests), value = summary.totalRequests); StatItem(label = stringResource(R.string.stats_total_input_tokens), value = summary.totalInputTokens); StatItem(label = stringResource(R.string.stats_total_output_tokens), value = summary.totalOutputTokens) }; Box(modifier = Modifier.padding(top = 16.dp)) { StatItem(label = stringResource(R.string.stats_estimated_cost), value = "¥ %.2f".format(summary.estimatedCostYuan), highlight = true) } } }
                item { Text(text = stringResource(R.string.stats_by_model), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold) }
                if (byModel.isEmpty()) item { EmptyState(title = stringResource(R.string.stats_no_data), subtitle = stringResource(R.string.stats_empty_hint)) }
                else items(byModel, key = { it.modelConfigId }) { stat -> ModelStatCard(stat = stat, onClick = { viewModel.selectModel(stat.modelConfigId) }) }
            } else {
                item { val sideLocal = CompareSideValues(requests = selectedDetail.requestCount.toLong(), inputTokens = selectedDetail.inputTokens.toLong(), outputTokens = selectedDetail.outputTokens.toLong(), costYuan = selectedDetail.estimatedCostYuan); ComparePanel(billingKind = selectedMeta.billingKind, sideLocal = sideLocal, remoteCompareView = remoteCompare, refreshState = remoteState, defaultRefreshMode = defaultRefreshMode, onRefresh = { viewModel.refreshRemoteUsage() }, onGoToModelEdit = { selectedMeta.config?.id?.let(onNavigateToModelEdit) }, onToggleRefreshMode = { m -> viewModel.setDefaultRefreshMode(m) }, onWebLoginClick = { val cfgId = selectedMeta.config?.id; if (cfgId != null) viewModel.openWebLogin(cfgId) }, modifier = Modifier.fillMaxWidth().padding(16.dp)) }
                ModelDetailSection(detail = selectedDetail, dailyPoints = selectedDaily, breakdown = selectedBreakdown)
            }
        }
    }
    if (showWebDialogId != null) com.llmhub.app.ui.billing_web.BillingWebBottomSheet(configId = showWebDialogId!!, range = range, onDismiss = { viewModel.dismissWebLogin() })
}
@Composable
private fun StatItem(label: String, value: Int, highlight: Boolean = false) = StatItem(label = label, value = value.toString(), highlight = highlight)
@Composable
private fun StatItem(label: String, value: String, highlight: Boolean = false) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(text = value, style = if (highlight) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun ModelStatCard(stat: ModelUsageStat, onClick: () -> Unit) {
    LLMHubCard(modifier = Modifier.padding(horizontal = 16.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) { Text(text = stat.modelName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(text = stat.provider.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(text = "¥ %.2f".format(stat.estimatedCostYuan), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.stats_request_count, stat.requestCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.stats_input_tokens, stat.inputTokens), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.stats_output_tokens, stat.outputTokens), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
// 软件：TRAE AI IDE | 大模型签名：Seedance 助手