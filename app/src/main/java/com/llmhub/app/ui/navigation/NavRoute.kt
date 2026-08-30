package com.llmhub.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.llmhub.app.ui.stats.TimeRange

/** 底部 Tab 路由定义。 */
sealed class NavRoute(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    data object Chat : NavRoute("chat", "对话", Icons.AutoMirrored.Outlined.Chat)
    data object Models : NavRoute("models", "模型", Icons.Outlined.Tune)
    data object Keys : NavRoute("keys", "密钥", Icons.Outlined.Key)
    data object Stats : NavRoute("stats", "统计", Icons.Outlined.BarChart)

    companion object {
        val all = listOf(Chat, Models, Keys, Stats)
        val startRoute = Chat.route
    }
}

/**
 * 非底部 Tab 的子路由：网页对账（从模型配置卡片或统计页进入）。
 *
 * 通过 NavGraph.composable(NavBillingWeb.route) 绑定全屏 [BillingWebFullScreen]。
 * 从统计页打开时则直接用 ModalBottomSheet，不走导航。
 */
object NavBillingWeb {
    const val ROUTE_TEMPLATE = "billing_web/{modelId}?range={range}"
    private const val ARG_MODEL_ID = "modelId"
    private const val ARG_RANGE = "range"
    val icon: ImageVector = Icons.Outlined.Public
    const val label = "网页对账"

    fun createRoute(modelId: Long, range: TimeRange = TimeRange.DAYS_7): String =
        "billing_web/$modelId?range=$range"

    fun parseModelId(args: Map<String, String?>): Long? =
        args[ARG_MODEL_ID]?.toLongOrNull()

    fun parseRange(args: Map<String, String?>): TimeRange =
        args[ARG_RANGE]?.let { runCatching { TimeRange.valueOf(it) }.getOrNull() }
            ?: TimeRange.DAYS_7
}
// 软件：TRAE AI IDE | 大模型签名：Seedance 助手