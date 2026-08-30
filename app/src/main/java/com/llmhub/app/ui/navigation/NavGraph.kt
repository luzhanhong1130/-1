package com.llmhub.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.llmhub.app.ui.billing_web.BillingWebFullScreen
import com.llmhub.app.ui.chat.ChatScreen
import com.llmhub.app.ui.keys.ApiKeyScreen
import com.llmhub.app.ui.models.ModelConfigScreen
import com.llmhub.app.ui.stats.UsageScreen

@Composable
fun LLMHubNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = NavRoute.startRoute,
        modifier = modifier,
    ) {
        composable(NavRoute.Chat.route) { ChatScreen() }
        composable(NavRoute.Models.route) {
            ModelConfigScreen(
                onNavigateToBillingWeb = { modelId, range ->
                    navController.navigate(NavBillingWeb.createRoute(modelId, range))
                },
            )
        }
        composable(NavRoute.Keys.route) { ApiKeyScreen() }
        composable(NavRoute.Stats.route) {
            UsageScreen(
                onNavigateToBillingWeb = { modelId, range ->
                    navController.navigate(NavBillingWeb.createRoute(modelId, range))
                },
            )
        }
        composable(
            route = NavBillingWeb.ROUTE_TEMPLATE,
            arguments = listOf(
                navArgument("modelId") { type = NavType.LongType },
                navArgument("range") {
                    type = NavType.StringType
                    defaultValue = "DAYS_7"
                    nullable = true
                },
            ),
        ) { backStackEntry ->
            val args = backStackEntry.arguments
            val modelId = args?.getLong("modelId") ?: -1L
            val rangeRaw = args?.getString("range")
            val range = rangeRaw?.let {
                runCatching { com.llmhub.app.ui.stats.TimeRange.valueOf(it) }.getOrNull()
            } ?: com.llmhub.app.ui.stats.TimeRange.DAYS_7
            if (modelId > 0L) {
                BillingWebFullScreen(
                    configId = modelId,
                    range = range,
                    onDismiss = { navController.popBackStack() },
                )
            } else {
                navController.popBackStack()
            }
        }
    }
}
// 软件：TRAE AI IDE | 大模型签名：Seedance 助手