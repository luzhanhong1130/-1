package com.llmhub.app.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.llmhub.app.R
import com.llmhub.app.data.model.DailyUsagePoint
import com.llmhub.app.data.model.ModelDetailStat
import com.llmhub.app.data.model.SuccessBreakdown
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun LazyListScope.ModelDetailSection(detail: ModelDetailStat, dailyPoints: List<DailyUsagePoint>, breakdown: SuccessBreakdown) {
    item { DetailHeaderCard(detail = detail) }
    item { SectionTitle(titleRes = R.string.stats_breakdown_title); BreakdownCard(breakdown = breakdown) }
    item { SectionTitle(titleRes = R.string.stats_daily_title) }
    if (dailyPoints.isEmpty()) item { Text(text = "该时间范围内暂无调用记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
    else items(dailyPoints, key = { it.dateBucket }) { point -> DailyPointRow(point = point) }
}
@Composable
private fun SectionTitle(titleRes: Int) {
    Text(text = stringResource(titleRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp))
}
@Composable
private fun DetailHeaderCard(detail: ModelDetailStat) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DetailStatCell(labelRes = R.string.stats_total_requests, value = detail.requestCount.toString())
                DetailStatCell(labelRes = R.string.stats_total_sessions, value = detail.sessionCount.toString())
                DetailStatCell(labelRes = R.string.stats_success_rate, value = formatRate(detail.successRate))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DetailStatCell(labelRes = R.string.stats_total_input_tokens, value = detail.inputTokens.toString())
                DetailStatCell(labelRes = R.string.stats_total_output_tokens, value = detail.outputTokens.toString())
                Box { DetailStatCell(labelRes = R.string.stats_estimated_cost, value = "¥ %.2f".format(detail.estimatedCostYuan), highlight = true) }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DetailStatCell(labelRes = R.string.stats_avg_latency, value = stringResource(R.string.stats_latency_ms, detail.avgLatencyMs))
                DetailStatCell(labelRes = R.string.stats_p50_latency, value = stringResource(R.string.stats_latency_ms, detail.p50LatencyMs))
                DetailStatCell(labelRes = R.string.stats_p95_latency, value = stringResource(R.string.stats_latency_ms, detail.p95LatencyMs))
            }
        }
    }
}
@Composable
private fun DetailStatCell(labelRes: Int, value: String, highlight: Boolean = false) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(text = value, style = if (highlight) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun BreakdownCard(breakdown: SuccessBreakdown) {
    val total = breakdown.successCount + breakdown.failOther
    val successPct = if (total <= 0) 0f else breakdown.successCount.toFloat() / total
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LinearProgressIndicator(progress = { successPct }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.errorContainer)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.stats_breakdown_success, breakdown.successCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.stats_breakdown_fail_other, breakdown.failOther), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
@Composable
private fun DailyPointRow(point: DailyUsagePoint) {
    val dateStr = rememberDayFormat(point.dateBucket)
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = dateStr, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(text = "¥ %.2f".format(point.estimatedCostYuan), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.stats_request_count, point.requestCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.stats_success, point.successCount, point.failCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.stats_input_tokens, point.inputTokens), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.stats_output_tokens, point.outputTokens), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
private fun formatRate(rate: Float): String = if (rate < 0f) "—" else String.format(Locale.getDefault(), "%.1f%%", rate * 100f)
@Composable
private fun rememberDayFormat(utcDayMillis: Long): String {
    val fmt = SimpleDateFormat("MM/dd", Locale.getDefault())
    return fmt.format(Date(utcDayMillis))
}
// 软件：TRAE AI IDE | 大模型签名：Seedance 助手