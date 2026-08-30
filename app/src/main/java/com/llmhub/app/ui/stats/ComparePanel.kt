package com.llmhub.app.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.llmhub.app.R
import com.llmhub.app.data.model.PlatformBillingKind
import com.llmhub.app.data.model.supportsWebLogin
import com.llmhub.app.data.prefs.RefreshMode
import com.llmhub.app.data.repository.RemoteCompareView

@Immutable
data class CompareSideValues(val requests: Long, val inputTokens: Long, val outputTokens: Long, val costYuan: Double, val currency: String = "CNY") {
    companion object { val ZERO = CompareSideValues(0, 0, 0, 0.0, "CNY") }
}

@Composable
fun ComparePanel(billingKind: PlatformBillingKind, sideLocal: CompareSideValues, remoteCompareView: RemoteCompareView, refreshState: RemoteUiState, defaultRefreshMode: RefreshMode, onRefresh: () -> Unit, onGoToModelEdit: () -> Unit, onToggleRefreshMode: (RefreshMode) -> Unit, onWebLoginClick: () -> Unit, modifier: Modifier = Modifier) {
    val supportsWeb = billingKind.supportsWebLogin
    val sideRemote = CompareSideValues(requests = remoteCompareView.requests, inputTokens = remoteCompareView.inputTokens, outputTokens = remoteCompareView.outputTokens, costYuan = remoteCompareView.costAmount, currency = remoteCompareView.costCurrency)
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.stats_compare_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (billingKind != PlatformBillingKind.DISABLED) OutlinedButton(onClick = onRefresh, enabled = refreshState != RemoteUiState.Loading) { Icon(Icons.Outlined.Refresh, null, modifier = Modifier.padding(end = 6.dp)); Text(stringResource(if (refreshState == RemoteUiState.Loading) R.string.stats_compare_syncing else R.string.stats_compare_sync_now)) }
            }
            if (billingKind == PlatformBillingKind.DISABLED) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(modifier = Modifier.weight(1f)) { Text(stringResource(R.string.stats_compare_empty_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold); Text(stringResource(R.string.stats_compare_empty_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)) }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Button(onClick = onGoToModelEdit, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) { Text(stringResource(R.string.stats_compare_go_setup)) } }
            } else {
                RemoteStatusBanner(statusWire = remoteCompareView.status, errorMessage = remoteCompareView.errorMessage, source = remoteCompareView.source, note = remoteCompareView.note, showWebAction = supportsWeb, onWebAction = onWebLoginClick)
                if (supportsWeb) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(onClick = onWebLoginClick, label = { Text(stringResource(R.string.stats_web_login_chip), maxLines = 1) }, leadingIcon = { Icon(Icons.Outlined.Public, null) })
                        Spacer(modifier = Modifier.weight(1f))
                        Text(text = stringResource(if (defaultRefreshMode == RefreshMode.PREFER_WEB) R.string.stats_web_mode_web else R.string.stats_web_mode_api), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Switch(checked = defaultRefreshMode == RefreshMode.PREFER_WEB, onCheckedChange = { checked -> onToggleRefreshMode(if (checked) RefreshMode.PREFER_WEB else RefreshMode.PREFER_API) })
                    }
                }
                when (refreshState) {
                    RemoteUiState.Loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.stats_compare_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    is RemoteUiState.Error -> Text(stringResource(R.string.stats_compare_error, refreshState.message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    is RemoteUiState.Data -> { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stats_compare_last_sync, java.text.SimpleDateFormat.getInstance().format(refreshState.syncedAtMillis)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); if (!remoteCompareView.source.isNullOrBlank() && remoteCompareView.source != com.llmhub.app.data.model.RemoteUsageSnapshot.SOURCE_API) { Spacer(Modifier.width(8.dp)); val sourceLabel = when (remoteCompareView.source) { com.llmhub.app.data.model.RemoteUsageSnapshot.SOURCE_WEB -> stringResource(R.string.stats_web_source_web); com.llmhub.app.data.model.RemoteUsageSnapshot.SOURCE_MANUAL -> stringResource(R.string.stats_web_source_manual); else -> remoteCompareView.source }; FilterChip(selected = false, onClick = {}, label = { Text(sourceLabel) }) } }
                    }
                    RemoteUiState.Idle -> Unit
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) { Text(stringResource(R.string.stats_compare_side_local), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp)); SideColumn(values = sideLocal) }
                    Box(modifier = Modifier.weight(1f)) { Column { Text(stringResource(R.string.stats_compare_side_remote) + "（${billingKind.displayName}）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(bottom = 4.dp)); SideColumn(values = sideRemote, placeholder = sideRemote.requests == 0L && sideRemote.inputTokens == 0L, placeholderText = "—") } }
                }
            }
        }
    }
}
@Composable
private fun SideColumn(values: CompareSideValues, placeholder: Boolean = false, placeholderText: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        StatLine(labelRes = R.string.stats_total_requests, value = if (placeholder) placeholderText else values.requests.toString())
        StatLine(labelRes = R.string.stats_total_input_tokens, value = if (placeholder) placeholderText else values.inputTokens.toString())
        StatLine(labelRes = R.string.stats_total_output_tokens, value = if (placeholder) placeholderText else values.outputTokens.toString())
        StatLine(labelRes = R.string.stats_estimated_cost, value = if (placeholder) placeholderText else formatAmount(values.costYuan, values.currency))
    }
}
private fun formatAmount(amount: Double, currency: String): String {
    val symbol = when (currency.uppercase()) { "CNY", "RMB" -> "¥ "; "USD" -> "$ "; "HKD" -> "HK$ "; "EUR" -> "€ "; "GBP" -> "£ "; "JPY" -> "¥ "; else -> "${currency.uppercase()} " }
    return "%s%.2f".format(symbol, amount)
}
@Composable
private fun RemoteStatusBanner(statusWire: String?, errorMessage: String?, source: String?, note: String?, showWebAction: Boolean, onWebAction: () -> Unit) {
    val kind = when (statusWire) {
        null, "OK" -> { if (source == com.llmhub.app.data.model.RemoteUsageSnapshot.SOURCE_MANUAL) BannerKind.MANUAL_REFERENCE else return }
        "NOT_SUPPORTED" -> BannerKind.NOT_SUPPORTED
        "AUTH_FAIL" -> BannerKind.AUTH_FAIL
        "RATE_LIMITED" -> BannerKind.RATE_LIMITED
        "NETWORK" -> BannerKind.NETWORK
        "PARSE_ERR" -> BannerKind.PARSE_ERR
        else -> BannerKind.UNKNOWN_ERR
    }
    val title: String; val subtitle: String; val colorKind: ColorKind
    when (kind) {
        BannerKind.NOT_SUPPORTED -> { title = stringResource(R.string.stats_banner_not_supported_title); subtitle = errorMessage ?: stringResource(R.string.stats_banner_not_supported_sub); colorKind = ColorKind.WARN }
        BannerKind.AUTH_FAIL -> { title = stringResource(R.string.stats_banner_auth_title); subtitle = errorMessage ?: stringResource(R.string.stats_banner_auth_sub); colorKind = ColorKind.ERR }
        BannerKind.RATE_LIMITED -> { title = stringResource(R.string.stats_banner_rate_title); subtitle = errorMessage ?: stringResource(R.string.stats_banner_rate_sub); colorKind = ColorKind.WARN }
        BannerKind.NETWORK -> { title = stringResource(R.string.stats_banner_network_title); subtitle = errorMessage ?: stringResource(R.string.stats_banner_network_sub); colorKind = ColorKind.WARN }
        BannerKind.PARSE_ERR -> { title = stringResource(R.string.stats_banner_parse_title); subtitle = errorMessage ?: stringResource(R.string.stats_banner_parse_sub); colorKind = ColorKind.ERR }
        BannerKind.UNKNOWN_ERR -> { title = stringResource(R.string.stats_banner_unknown_title); subtitle = errorMessage ?: stringResource(R.string.stats_banner_unknown_sub); colorKind = ColorKind.ERR }
        BannerKind.MANUAL_REFERENCE -> { title = stringResource(R.string.stats_banner_manual_reference); subtitle = note?.takeIf { it.isNotBlank() } ?: stringResource(R.string.stats_web_keep_reference_subtitle); colorKind = ColorKind.INFO }
    }
    val containerColor = when (colorKind) { ColorKind.WARN -> MaterialTheme.colorScheme.tertiaryContainer; ColorKind.ERR -> MaterialTheme.colorScheme.errorContainer; ColorKind.INFO -> MaterialTheme.colorScheme.secondaryContainer }
    val onContainer = when (colorKind) { ColorKind.WARN -> MaterialTheme.colorScheme.onTertiaryContainer; ColorKind.ERR -> MaterialTheme.colorScheme.onErrorContainer; ColorKind.INFO -> MaterialTheme.colorScheme.onSecondaryContainer }
    val needAction = showWebAction && kind.let { it == BannerKind.AUTH_FAIL || it == BannerKind.NOT_SUPPORTED || it == BannerKind.PARSE_ERR || it == BannerKind.NETWORK }
    Card(colors = CardDefaults.cardColors(containerColor = containerColor), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(imageVector = Icons.Outlined.CloudOff, contentDescription = null, tint = onContainer)
                Column(modifier = Modifier.weight(1f)) { Text(text = title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = onContainer); Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = onContainer.copy(alpha = 0.85f), modifier = Modifier.padding(top = 2.dp)) }
            }
            if (needAction) Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End) { TextButton(onClick = onWebAction) { Icon(Icons.Outlined.Public, null, modifier = Modifier.padding(end = 6.dp), tint = onContainer); Text(stringResource(R.string.stats_banner_try_web_login), color = onContainer) } }
        }
    }
}
private enum class BannerKind { NOT_SUPPORTED, AUTH_FAIL, RATE_LIMITED, NETWORK, PARSE_ERR, UNKNOWN_ERR, MANUAL_REFERENCE }
private enum class ColorKind { WARN, ERR, INFO }
@Composable
private fun StatLine(labelRes: Int, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
// 软件：TRAE AI IDE | 大模型签名：Seedance 助手