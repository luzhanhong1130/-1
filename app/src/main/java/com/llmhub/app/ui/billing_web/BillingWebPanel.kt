package com.llmhub.app.ui.billing_web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.llmhub.app.R
import com.llmhub.app.data.billing.web.WebCookieManager
import com.llmhub.app.ui.stats.TimeRange
import com.llmhub.app.ui.stats.UNSELECTED_MODEL_ID
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingWebBottomSheet(configId: Long, range: TimeRange, onDismiss: () -> Unit) {
    require(configId != UNSELECTED_MODEL_ID) { "BillingWebBottomSheet requires a specific ModelConfig ID." }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { BottomSheetDefaults.DragHandle() }, containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        BillingWebHost(configId = configId, range = range, onDismiss = dismiss, modifier = Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingWebFullScreen(configId: Long, range: TimeRange = TimeRange.DAYS_7, onDismiss: () -> Unit) {
    val dismiss = remember(onDismiss) { onDismiss }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BillingWebHost(configId = configId, range = range, onDismiss = dismiss, modifier = Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BillingWebHost(configId: Long, range: TimeRange, onDismiss: () -> Unit, modifier: Modifier = Modifier, viewModel: BillingWebViewModel = hiltViewModel()) {
    LaunchedEffect(configId, range) { viewModel.bind(configId, range) }
    val dismissRequested by viewModel.requestDismiss.collectAsStateWithLifecycle()
    LaunchedEffect(dismissRequested) { if (dismissRequested) { viewModel.onRequestDismissHandled(); onDismiss() } }
    BillingWebContent(viewModel = viewModel, onDismiss = onDismiss, modifier = modifier)
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BillingWebContent(viewModel: BillingWebViewModel, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentUrl by viewModel.currentUrl.collectAsStateWithLifecycle()
    val loadProgress by viewModel.loadProgress.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val extractState by viewModel.extractState.collectAsStateWithLifecycle()
    val extractMessage by viewModel.extractMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    extractMessage?.let { msg -> LaunchedEffect(msg) { snackbarHostState.showSnackbar(message = msg); viewModel.consumeExtractMessage() } }
    var webViewRef: WebView? by remember { mutableStateOf(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var showCookieMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    BackHandler(enabled = canGoBack) { webViewRef?.goBack() }
    Scaffold(modifier = modifier, topBar = { Column { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), title = { val title = when (val s = uiState) { BillingWebUiState.Loading -> context.getString(R.string.loading); BillingWebUiState.ConfigNotFound -> "模型配置不存在"; is BillingWebUiState.Ready -> buildString { append(s.config.name); append(" · "); append(s.config.billingEndpointKind.displayName) } }; Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }, navigationIcon = { IconButton(onClick = onDismiss) { Icon(imageVector = Icons.Outlined.Close, contentDescription = context.getString(R.string.action_close)) } }, actions = { IconButton(onClick = { webViewRef?.goBack() }, enabled = canGoBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) }; IconButton(onClick = { webViewRef?.goForward() }, enabled = canGoForward) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null) }; IconButton(onClick = { webViewRef?.reload() }, enabled = uiState is BillingWebUiState.Ready) { if (isLoading) CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant) else Icon(Icons.Outlined.Refresh, null) }; Box { IconButton(onClick = { showCookieMenu = !showCookieMenu }) { Icon(Icons.Outlined.Cookie, null) }; DropdownMenu(expanded = showCookieMenu, onDismissRequest = { showCookieMenu = false }) { DropdownMenuItem(text = { Text("清理当前站点 Cookie") }, onClick = { showCookieMenu = false; val host = currentUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() }; viewModel.clearCookies(host); scope.launch { snackbarHostState.showSnackbar(if (host.isNullOrBlank()) "已清理全部 Cookie" else "已清理 $host 的 Cookie，下次进入请重新登录") }; val start = (uiState as? BillingWebUiState.Ready)?.startUrl; if (start != null) webViewRef?.loadUrl(start) else webViewRef?.reload() }); DropdownMenuItem(text = { Text("清理所有 Cookie") }, onClick = { showCookieMenu = false; viewModel.clearCookies(null); scope.launch { snackbarHostState.showSnackbar("已清理所有站点 Cookie") }; (uiState as? BillingWebUiState.Ready)?.startUrl?.let { webViewRef?.loadUrl(it) } ?: webViewRef?.reload() }) } } }) }; if (isLoading && loadProgress in 1..99) LinearProgressIndicator(progress = { loadProgress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.fillMaxWidth().height(2.dp)) } }, bottomBar = { BillingWebBottomActionBar(extractState = extractState, onExtractClick = { val wv = webViewRef; if (wv != null) viewModel.onExtractClicked(wv) else scope.launch { snackbarHostState.showSnackbar("页面尚未加载完成，请稍候再试。") } }, onKeepReferenceClick = { viewModel.onKeepReferenceClick() }, enabled = uiState is BillingWebUiState.Ready && (uiState as BillingWebUiState.Ready).startUrl != null) }, snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding -> Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) { when (val s = uiState) { BillingWebUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; BillingWebUiState.ConfigNotFound -> BillingWebEmptyOverlay(title = "模型配置不存在", subtitle = "可能已被删除。请返回并重新选择。", actionLabel = "关闭", onAction = onDismiss); is BillingWebUiState.Ready -> { val ready = s; if (ready.startUrl == null) BillingWebEmptyOverlay(title = "无法打开网页对账", subtitle = ready.errorHeader ?: "当前平台暂时没有可用的登录入口。", actionLabel = "关闭", onAction = onDismiss) else Column(Modifier.fillMaxSize()) { BillingWebNoticeCard(kindDisplayName = ready.config.billingEndpointKind.displayName, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)); Box(modifier = Modifier.fillMaxSize()) { AndroidView(factory = { ctx -> WebView(ctx).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); configureWebViewSettings(this.settings); WebCookieManager.ensureInitialized(); WebCookieManager.afterWebViewCreated(this); webViewClient = object : WebViewClient() { override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { viewModel.onPageStarted(); super.onPageStarted(view, url, favicon) } override fun onPageFinished(view: WebView?, url: String?) { super.onPageFinished(view, url); viewModel.onPageFinished(url); view?.let { canGoBack = it.canGoBack(); canGoForward = it.canGoForward() } } override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) { super.onReceivedError(view, request, error); viewModel.onPageFinished(request?.url?.toString()) } override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean { val url = request.url.toString(); val prefix = ready.postLoginPrefix; if (prefix != null && url.startsWith(prefix) && ready.usageUrl != null) { view?.post { view.loadUrl(ready.usageUrl) }; return true } return super.shouldOverrideUrlLoading(view, request) } }; webChromeClient = object : WebChromeClient() { override fun onProgressChanged(view: WebView?, newProgress: Int) { super.onProgressChanged(view, newProgress); viewModel.onProgressChanged(newProgress) } }; isScrollContainer = true; setOnTouchListener { v, event -> when (event.actionMasked) { MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { v.parent?.requestDisallowInterceptTouchEvent(true) }; MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.parent?.requestDisallowInterceptTouchEvent(false) } }; false }; webViewRef = this; loadUrl(ready.startUrl) } }, update = { view -> canGoBack = view.canGoBack(); canGoForward = view.canGoForward(); webViewRef = view }, modifier = Modifier.fillMaxSize()); if (isLoading && loadProgress < 10) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)), contentAlignment = Alignment.TopCenter) { LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 16.dp, end = 16.dp)) } } } } } }
    DisposableEffect(Unit) { onDispose { webViewRef?.apply { stopLoading(); loadUrl("about:blank"); removeAllViews(); (parent as? ViewGroup)?.removeView(this); destroy() }; webViewRef = null } }
}

@Composable
private fun BillingWebBottomActionBar(extractState: ExtractUiState, onExtractClick: () -> Unit, onKeepReferenceClick: () -> Unit, enabled: Boolean) {
    Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onKeepReferenceClick, enabled = enabled && extractState != ExtractUiState.Running, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.PhotoLibrary, null, modifier = Modifier.padding(end = 6.dp)); Text("保留参考视图", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Button(onClick = onExtractClick, enabled = enabled && extractState != ExtractUiState.Running, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { if (extractState == ExtractUiState.Running) CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp).padding(end = 6.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Icon(Icons.Outlined.DataObject, null, modifier = Modifier.padding(end = 6.dp)); Text(text = when (extractState) { ExtractUiState.Idle -> "从当前页抽取"; ExtractUiState.Running -> "抽取中…"; ExtractUiState.Succeeded -> "重新抽取"; ExtractUiState.Failed -> "重试抽取" }, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Text(text = when (extractState) { ExtractUiState.Succeeded -> "已成功抽取并写入后台对账栏。"; ExtractUiState.Failed -> "未抽取到可用数据。请确认是否停在正确的用量页，或点「保留参考视图」。"; else -> "提示：登录后如果页面未自动跳转到用量/账单页，请手动导航到该页再点抽取。" }, style = MaterialTheme.typography.bodySmall, color = when (extractState) { ExtractUiState.Succeeded -> MaterialTheme.colorScheme.primary; ExtractUiState.Failed -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant })
        }
    }
}

@Composable
private fun BillingWebNoticeCard(kindDisplayName: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "$kindDisplayName · 网页对账", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(text = "登录时可能需要手动完成人机验证。Cookie 仅保存在本机，随时可一键清除。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.88f))
            }
        }
    }
}

@Composable
private fun BillingWebEmptyOverlay(title: String, subtitle: String, actionLabel: String, onAction: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Button(onClick = onAction, modifier = Modifier.padding(top = 20.dp)) { Text(actionLabel) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureWebViewSettings(settings: WebSettings) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    settings.cacheMode = WebSettings.LOAD_DEFAULT
}
// 软件：TRAE AI IDE | 大模型签名：Seedance 助手