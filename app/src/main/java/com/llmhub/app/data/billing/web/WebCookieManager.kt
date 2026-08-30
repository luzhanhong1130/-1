package com.llmhub.app.data.billing.web

import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * WebView Cookie 管理：初始化 / 按 host 清理 / 登录态健康检查。
 *
 * 为什么不用 EncryptedSharedPreferences 存 Cookie：
 *   WebView Cookie 本身是加密存在 app_webview/Cookies 数据库里（Android 9+），
 *   并且由系统做安全隔离；直接复用 CookieManager 是最稳妥的方式。
 */
object WebCookieManager {

    private const val TAG = "WebCookieMgr"

    /** 是否已经初始化（Application.onCreate 中调用一次即可）。 */
    @Volatile private var initialized: Boolean = false

    /**
     * 初始化：
     *  - `setAcceptCookie(true)`
     *  - `setAcceptThirdPartyCookies(true)`：One API/SPA 的第三方登录（如 GitHub SSO）依赖第三方 Cookie 被保留
     *  - flush：立即写到磁盘，下次启动保留
     */
    @Synchronized
    fun ensureInitialized() {
        if (initialized) return
        runCatching {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            // setAcceptThirdPartyCookies 签名：(WebView, Boolean)。没有现成 WebView 时：
            //   SDK 21+ 默认行为不依赖这个方法也能工作；这里用反射避免构造 WebView（省内存）。
            //   需要时我们在 BillingWebPanel 创建 WebView 后再次调用。
            cm.flush()
        }.onFailure {
            Log.w(TAG, "ensureInitialized failed: ${it.message}")
        }
        initialized = true
    }

    /** 创建 WebView 时调用：确保第三方 Cookie 允许（SPA SSO）。 */
    fun afterWebViewCreated(webView: WebView) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            }
        }
    }

    /** 获取当前 CookieManager（便于 BillingWebPanel 直接使用）。 */
    fun manager(): CookieManager = CookieManager.getInstance()

    // ------------------------------------------------------------------
    // 清理
    // ------------------------------------------------------------------

    /**
     * 清理 Cookie。
     *
     * @param hostOrNull 非 null：只清理该 host（及其子域名）的 Cookie；null：全部清理。
     */
    suspend fun clearCookies(hostOrNull: String?) = withContext(Dispatchers.Main) {
        ensureInitialized()
        val cm = CookieManager.getInstance()
        if (hostOrNull == null) {
            cm.removeAllCookies(null)
            cm.flush()
            Log.i(TAG, "clearCookies: ALL removed")
        } else {
            // CookieManager 没有直接按 host 删除的 API，做法是读该 host 的 Cookie 再逐个设为空+过期。
            // 这里取最严格的方式：对 host、www.host 前缀做 setCookie("=;expires=...")。
            val normalized = hostOrNull.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/')
            val hosts = listOfNotNull(
                normalized,
                if (normalized.startsWith("www.")) normalized.removePrefix("www.") else "www.$normalized",
            ).distinct()
            for (h in hosts) {
                val snapshot: String? = cm.getCookie("https://$h/")
                if (snapshot.isNullOrBlank()) continue
                runCatching {
                    snapshot.split(';').map { it.trim().substringBefore('=') }.forEach { name ->
                        if (name.isNotBlank()) {
                            cm.setCookie("https://$h/", "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Domain=.$h")
                            cm.setCookie("https://$h/", "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0")
                        }
                    }
                }
            }
            cm.flush()
            Log.i(TAG, "clearCookies: host=$normalized scoped removed")
        }
    }

    /** 同步版本（非协程上下文，如顶部 Toolbar 按钮点击）。 */
    fun clearCookiesBlocking(hostOrNull: String?) {
        ensureInitialized()
        val cm = CookieManager.getInstance()
        if (hostOrNull == null) {
            cm.removeAllCookies(null)
            cm.flush()
        } else {
            val normalized = hostOrNull.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/')
            val hosts = listOfNotNull(
                normalized,
                if (normalized.startsWith("www.")) normalized.removePrefix("www.") else "www.$normalized",
            ).distinct()
            for (h in hosts) {
                val snapshot: String? = cm.getCookie("https://$h/")
                if (snapshot.isNullOrBlank()) continue
                runCatching {
                    snapshot.split(';').map { it.trim().substringBefore('=') }.forEach { name ->
                        if (name.isNotBlank()) {
                            cm.setCookie("https://$h/", "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Domain=.$h")
                            cm.setCookie("https://$h/", "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0")
                        }
                    }
                }
            }
            cm.flush()
        }
    }

    // ------------------------------------------------------------------
    // 登录态检查（非核心路径，失败直接返回 false 即可）
    // ------------------------------------------------------------------

    /**
     * 在没有真实 WebView 的情况下，测试指定 host 是否已登录。
     * 规则：对 [probeUrl] 发 HEAD 请求，把 CookieManager 中该域的 Cookie 一起带上；
     * 若返回码不是 3xx（跳登录）且 body 非 401，则认为登录。
     *
     * 注意：有一定误判率，仅作为 UI 提示辅助。
     */
    suspend fun isLoggedIn(host: String, probePath: String = "/"): Boolean = withContext(Dispatchers.IO) {
        val cm = manager()
        val baseUrl = "https://$host"
        val cookieHeader = cm.getCookie(baseUrl).orEmpty()
        if (cookieHeader.isBlank()) return@withContext false
        runCatching {
            val url = URL("https://$host${if (probePath.startsWith('/')) probePath else "/$probePath"}")
            val conn: HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("Cookie", cookieHeader)
            }
            try {
                val code = conn.responseCode
                code in 200..299 || code in 400..499 && code != 401 && code != 403
            } finally {
                runCatching { conn.disconnect() }
            }
        }.getOrDefault(false)
    }

    /**
     * 在一个 WebView 里执行 JS 表达式，挂起直到拿到返回值（JSON 字符串）。
     * 方便 WebExtractOrchestrator 复用，避免每个平台都手写 ValueCallback 样板。
     */
    suspend fun evaluateJs(webView: WebView, script: String): String? = suspendCancellableCoroutine { cont ->
        webView.post {
            val callback = ValueCallback<String?> { result ->
                if (cont.isActive) cont.resume(result)
            }
            webView.evaluateJavascript(script, callback)
        }
    }
}
