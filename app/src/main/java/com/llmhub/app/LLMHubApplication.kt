package com.llmhub.app

import android.app.Application
import com.llmhub.app.data.billing.BillingDateUtils
import com.llmhub.app.data.billing.web.WebCookieManager
import com.llmhub.app.data.repository.RemoteUsageRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LLMHubApplication : Application() {

    @Inject lateinit var remoteUsageRepository: RemoteUsageRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // Web 登录子系统初始化：接受 Cookie / 刷到磁盘。
        // 必须在任何 WebView 创建前调用。
        WebCookieManager.ensureInitialized()

        // 启动时一次性清掉超过 60 天的后台用量快照（FK CASCADE 自动删 daily 点），
        // 避免用量明细长期堆积挤占本地存储。
        appScope.launch {
            runCatching {
                remoteUsageRepository.purgeOldSnapshots(BillingDateUtils.KEEP_DAYS_MS)
            }.onFailure {
                // 清理失败不影响启动，静默吞掉
            }
        }
    }
}
// 软件：TRAE AI IDE | 大模型签名：Seedance 助手