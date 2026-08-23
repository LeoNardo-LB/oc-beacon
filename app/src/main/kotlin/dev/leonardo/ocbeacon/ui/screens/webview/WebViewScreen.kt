package dev.leonardo.ocbeacon.ui.screens.webview

import dev.leonardo.ocbeacon.logging.AppLogger

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import dev.leonardo.ocbeacon.BuildConfig
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.foundation.background
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.repository.ServerConfigRepository
import kotlinx.coroutines.flow.SharedFlow

/**
 * WebView 屏幕 — 加载远程 OpenCode Web UI
 *
 * 旧版 WebView 回退路径（useNativeUi=true 硬编码，原生 UI 为现役）：
 * 加载 OpenCode 服务器提供的 Web UI，同时 Android 前台服务在后台保持 SSE 连接活跃。
 *
 * 特性：
 * - 下拉刷新手势触发页面重载
 * - 系统返回键在 WebView 历史中导航
 * - 全屏（无顶栏）
 * - 即使 WebView 已打开，也会响应深度链接导航事件（navigateUrlFlow），
 *   通过在已有实例上调用 loadUrl() 实现。
 */
@Composable
fun WebViewScreen(
    serverId: String,
    initialPath: String = "",
    serverConfigRepository: ServerConfigRepository,
    navigateUrlFlow: SharedFlow<String>? = null,
    isDarkTheme: Boolean = false,
    onNavigateBack: () -> Unit
) {
    // 异步加载服务器配置（含明文用户名/密码，仅用于 WebView Basic Auth —— 明文凭据不再经导航参数传递）
    var serverConfig by remember { mutableStateOf<ServerConfig?>(null) }
    var configLoadError by remember { mutableStateOf(false) }

    LaunchedEffect(serverId) {
        val config = serverConfigRepository.getServer(serverId)
        if (config != null) {
            serverConfig = config
        } else {
            configLoadError = true
        }
    }

    // 构建完整 URL：serverUrl + initialPath（用于会话深度链接）
    val serverUrl: String = serverConfig?.url ?: ""
    val fullUrl = remember(serverUrl, initialPath) {
        if (initialPath.isNotBlank() && serverUrl.isNotBlank()) {
            serverUrl.trimEnd('/') + initialPath
        } else {
            serverUrl
        }
    }

    if (BuildConfig.DEBUG) AppLogger.d("WebViewScreen", "Composable invoked: serverId=$serverId, initialPath=$initialPath, fullUrl=$fullUrl")
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }

    // 支持 WebView 中 <input type="file"> 的文件选择器
    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (BuildConfig.DEBUG) AppLogger.d("WebViewScreen", "File chooser result: ${uris.size} files selected")
        fileChooserCallback?.onReceiveValue(uris.toTypedArray())
        fileChooserCallback = null
    }

    // 从加载后的配置派生 Basic Auth 头（密码不出现在导航参数中）
    val username: String = serverConfig?.username ?: ""
    val password: String = serverConfig?.password ?: ""
    val authHeader = remember(username, password) {
        if (username.isNotBlank() && password.isNotBlank()) {
            val credentials = "$username:$password"
            "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        } else {
            null
        }
    }
    
    // 监听来自深度链接的导航事件（WebView 打开时的通知点击）
    LaunchedEffect(navigateUrlFlow) {
        navigateUrlFlow?.collect { newUrl ->
            AppLogger.i("WebViewScreen", "Deep-link navigation received: $newUrl")
            webView?.let { wv ->
                val headers = authHeader?.let { mapOf("Authorization" to it) } ?: emptyMap()
                wv.loadUrl(newUrl, headers)
            }
        }
    }

    // 刷新处理器
    fun refresh() {
        webView?.let { wv ->
            isRefreshing = true
            val headers = authHeader?.let { mapOf("Authorization" to it) } ?: emptyMap()
            wv.loadUrl(serverUrl, headers)
        }
    }

    // 处理系统返回键：在 WebView 历史中后退，处于根页面时退出
    BackHandler {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            onNavigateBack()
        }
    }

    // 确保导航离开时销毁 WebView，避免渲染进程和 Activity 引用泄漏
    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            }
            webView = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { refresh() },
            state = rememberPullToRefreshState(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()  // 为状态栏添加内边距
                    .navigationBarsPadding()  // 为导航栏添加内边距
                    .imePadding()  // 键盘弹出时收缩
            ) {
            // WebView
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    @SuppressLint("SetJavaScriptEnabled")
                    val wv = WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowContentAccess = true
                            allowFileAccess = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            // 允许 WebSocket 连接
                            javaScriptCanOpenWindowsAutomatically = true
                            // 缓存设置，改善离线体验
                            cacheMode = WebSettings.LOAD_DEFAULT
                            // User agent
                            userAgentString = "$userAgentString OpenCodeAndroid/1.0"
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                if (BuildConfig.DEBUG) AppLogger.d("WebViewScreen", "Page started: $url")
                                isLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (BuildConfig.DEBUG) AppLogger.d("WebViewScreen", "Page finished: $url")
                                isLoading = false
                                isRefreshing = false
                                // 注入主题以匹配应用的深/浅色模式
                                val themeJs = if (isDarkTheme) {
                                    """
                                    (function() {
                                        document.documentElement.classList.add('dark');
                                        document.documentElement.style.colorScheme = 'dark';
                                        var meta = document.querySelector('meta[name="color-scheme"]');
                                        if (meta) meta.content = 'dark';
                                    })();
                                    """
                                } else {
                                    """
                                    (function() {
                                        document.documentElement.classList.remove('dark');
                                        document.documentElement.style.colorScheme = 'light';
                                        var meta = document.querySelector('meta[name="color-scheme"]');
                                        if (meta) meta.content = 'light';
                                    })();
                                    """
                                }
                                view?.evaluateJavascript(themeJs, null)
                            }

                            override fun onReceivedHttpAuthRequest(
                                view: WebView?,
                                handler: HttpAuthHandler?,
                                host: String?,
                                realm: String?
                            ) {
                                if (BuildConfig.DEBUG) AppLogger.d("WebViewScreen", "HTTP Auth requested for host=$host, realm=$realm")
                                if (username.isNotBlank()) {
                                    handler?.proceed(username, password)
                                } else {
                                    handler?.cancel()
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                // #152：主帧错误 w（页面加载失败是用户可感知事件）；
                                // 子资源错误（图片 404 等）是常态噪音——降 d 不进错误队列
                                if (request?.isForMainFrame == true) {
                                    AppLogger.w("WebViewScreen", "Main-frame error: ${request?.url}: ${error?.description} (code=${error?.errorCode})")
                                } else if (BuildConfig.DEBUG) {
                                    AppLogger.d("WebViewScreen", "Sub-resource error: ${request?.url} (code=${error?.errorCode})")
                                }
                                // 只处理主帧错误
                                if (request?.isForMainFrame == true) {
                                    isLoading = false
                                    isRefreshing = false
                                }
                            }

                            // 同源导航保持在 WebView 内
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val requestUrl = request?.url?.toString() ?: return false
                                // 同源请求留在 WebView 中
                                if (requestUrl.startsWith(serverUrl)) {
                                    return false
                                }
                                // 相对 URL 也留在 WebView（它们会解析为同源）
                                return false
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                isLoading = newProgress < 100
                            }

                            override fun onShowFileChooser(
                                webView: WebView?,
                                callback: ValueCallback<Array<Uri>>?,
                                params: FileChooserParams?
                            ): Boolean {
                                if (BuildConfig.DEBUG) AppLogger.d("WebViewScreen", "onShowFileChooser: mode=${params?.mode}, acceptTypes=${params?.acceptTypes?.toList()}")
                                // 取消之前未完成的回调
                                fileChooserCallback?.onReceiveValue(null)
                                fileChooserCallback = callback

                                val mimeTypes = params?.acceptTypes
                                    ?.filter { it.isNotBlank() }
                                    ?.toTypedArray()
                                    ?: arrayOf("*/*")
                                if (mimeTypes.isEmpty()) {
                                    fileChooserLauncher.launch(arrayOf("*/*"))
                                } else {
                                    fileChooserLauncher.launch(mimeTypes)
                                }
                                return true
                            }
                        }
                    }

                    // 加载完整 URL（config 加载完成、fullUrl 非空时）
                    if (fullUrl.isNotBlank()) {
                        val headers = authHeader?.let { mapOf("Authorization" to it) } ?: emptyMap()
                        wv.loadUrl(fullUrl, headers)
                    }

                    webView = wv
                    wv
                },
                update = { /* WebView 状态由内部管理 */ }
            )

            // config 异步加载完成后，触发首次加载（factory 已执行但 fullUrl 当时为空）
            LaunchedEffect(fullUrl, authHeader) {
                if (fullUrl.isNotBlank() && webView != null) {
                    // 仅在 WebView 尚未加载任何页面时触发，避免覆盖用户导航
                    if (webView?.url.isNullOrBlank()) {
                        val headers = authHeader?.let { mapOf("Authorization" to it) } ?: emptyMap()
                        webView?.loadUrl(fullUrl, headers)
                    }
                }
            }

            // 加载指示器覆盖层
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.FAINT)
                )
            }
        }
    }
    }
}
