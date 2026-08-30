package com.llmhub.app.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.llmhub.app.R
import com.llmhub.app.data.model.ModelUsageStat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorDropdown(modelStats: List<ModelUsageStat>, selectedModelId: Long, onSelected: (Long?) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = when {
        selectedModelId == UNSELECTED_MODEL_ID -> stringResource(R.string.stats_model_global)
        else -> modelStats.firstOrNull { it.modelConfigId == selectedModelId }?.modelName ?: stringResource(R.string.stats_model_deleted)
    }
    val selectedSub = when {
        selectedModelId == UNSELECTED_MODEL_ID -> stringResource(R.string.stats_model_global_subtitle)
        else -> modelStats.firstOrNull { it.modelConfigId == selectedModelId }?.provider?.displayName ?: ""
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = modifier) {
        OutlinedTextField(value = selectedLabel, onValueChange = {}, readOnly = true, label = { Text(stringResource(R.string.stats_model_selector_hint)) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, supportingText = { Text(selectedSub, fontWeight = FontWeight.Normal) }, leadingIcon = { Icon(if (selectedModelId == UNSELECTED_MODEL_ID) Icons.Outlined.Dashboard else Icons.Outlined.Extension, contentDescription = null) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true))
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Column(verticalArrangement = Arrangement.spacedBy((-2).dp)) { Text(stringResource(R.string.stats_model_global), fontWeight = if (selectedModelId == UNSELECTED_MODEL_ID) FontWeight.SemiBold else FontWeight.Normal); Text(stringResource(R.string.stats_model_global_subtitle), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant) } }, onClick = { onSelected(null); expanded = false })
            modelStats.forEach { stat -> val isSel = stat.modelConfigId == selectedModelId; DropdownMenuItem(text = { Column(verticalArrangement = Arrangement.spacedBy((-2).dp)) { Text(stat.modelName, fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal); Text(stat.provider.displayName, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant) } }, onClick = { onSelected(stat.modelConfigId); expanded = false }) }
        }
    }
}
// 软件：TRAE AI IDE | 大模型签名：Seedance 助手