package com.llmhub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.llmhub.app.ui.components.LLMHubAppScaffold
import com.llmhub.app.ui.navigation.LLMHubNavGraph
import com.llmhub.app.ui.theme.LLMHubTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LLMHubTheme {
                val navController = rememberNavController()
                LLMHubAppScaffold(navController = navController) { padding ->
                    LLMHubNavGraph(
                        navController = navController,
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
    }
}
// 软件：TRAE AI IDE | 大模型签名：Seedance 助手