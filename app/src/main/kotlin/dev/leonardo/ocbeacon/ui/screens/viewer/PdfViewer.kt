package dev.leonardo.ocbeacon.ui.screens.viewer

import dev.leonardo.ocbeacon.logging.AppLogger

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

private const val TAG = "PdfViewer"

/**
 * 在 WebView 中使用 PDF.js 的 PDF 查看器。
 * 加载 base64 编码的 PDF 数据并把页面渲染到 canvas。
 *
 * 关键修复：`allowFileAccessFromFileURLs = true` 让 pdf.js Web Worker
 * 能从 `file://` 协议加载（渲染必需）。
 *
 * @param base64Data 来自 API 的 base64 编码 PDF 内容
 * @param visible 查看器是否可见
 * @param modifier Compose modifier
 */
@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION") // allowFileAccessFromFileURLs/allowUniversalAccessFromFileURLs 是 pdf.js worker 必需，无替代 API
@Composable
fun PdfViewer(
    base64Data: String,
    visible: Boolean = true,
    modifier: Modifier = Modifier
) {
    var totalPages by remember { mutableIntStateOf(0) }
    var currentPage by remember { mutableIntStateOf(1) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val escapedBase64 = remember(base64Data) {
        // 移除换行符：MIME base64 每 76 字符插入 \n（RFC 2045），
        // 换行符在 JS 字符串字面量中导致 SyntaxError。
        // atob() 解码时自动忽略换行符，所以移除是安全的。
        base64Data.replace("\n", "").replace("\r", "")
    }

    // composable 离开组合时清理 WebView
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                // L-6：先移除 JS 桥再销毁（与 CodeWebView 一致——addJavascriptInterface
                // 的对象由 WebView 强引用，不移除则泄漏到 JavaBridge 线程）。
                removeJavascriptInterface("PdfViewerInterface")
                (parent as? android.view.ViewGroup)?.removeView(this)
                destroy()
            }
            webViewRef = null
        }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        allowFileAccess = true
                        allowContentAccess = false
                        // 关键：允许 Web Worker 从 file:// 协议加载。
                        // 没有这一项，pdf.js 无法创建 worker 并静默失败。
                        allowFileAccessFromFileURLs = true
                        allowUniversalAccessFromFileURLs = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        loadWithOverviewMode = true
                        useWideViewPort = true
                    }

                    // 用于接收 pdf_viewer.html 回调的 JS 接口
                    // 注意：@JavascriptInterface 方法运行在 WebView 的 JavaBridge
                    // 线程上，不是主线程。必须 post 到主线程才能安全修改 Compose 状态。
                    val mainHandler = Handler(Looper.getMainLooper())

                    addJavascriptInterface(
                        object {
                            @android.webkit.JavascriptInterface
                            fun onPdfLoaded(total: Int) {
                                mainHandler.post {
                                    totalPages = total
                                    isLoading = false
                                }
                            }

                            @android.webkit.JavascriptInterface
                            fun onPageRendered(current: Int, total: Int) {
                                mainHandler.post {
                                    currentPage = current
                                    totalPages = total
                                }
                            }

                            @android.webkit.JavascriptInterface
                            fun onError(message: String) {
                                AppLogger.e(TAG, "PDF.js error: $message")
                                mainHandler.post {
                                    isLoading = false
                                    hasError = true
                                    errorMessage = message
                                }
                            }
                        },
                        "PdfViewerInterface"
                    )

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            AppLogger.d(TAG, "Page finished loading, injecting PDF data")
                            view?.evaluateJavascript(
                                "loadPdfFromBase64('$escapedBase64')",
                                null
                            )
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            AppLogger.e(TAG, "WebView error: ${error?.description}")
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            AppLogger.d(TAG, "JS Console [${consoleMessage.messageLevel()}]: ${consoleMessage.message()}")
                            return true
                        }
                    }

                    loadUrl("file:///android_asset/pdfjs/pdf_viewer.html")
                }
            },
            update = { webView ->
                webView.visibility = if (visible) View.VISIBLE else View.GONE
                webViewRef = webView
            }
        )

        // ── 工具栏覆盖层（翻页） ──
        if (!isLoading && !hasError && totalPages > 0) {
            Surface(
                // #137（D2-L49）：裸 alpha 0.9f → AlphaTokens.AMOLED（数值最接近 0.92）
                color = MaterialTheme.colorScheme.surface.copy(alpha = AlphaTokens.AMOLED),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SpacingTokens.SM.dp, vertical = SpacingTokens.XS.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            webViewRef?.evaluateJavascript("prevPage()", null)
                        },
                        enabled = currentPage > 1
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.pdf_previous_page)
                        )
                    }
                    Text(
                        text = "$currentPage / $totalPages",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = SpacingTokens.MD.dp)
                    )
                    IconButton(
                        onClick = {
                            webViewRef?.evaluateJavascript("nextPage()", null)
                        },
                        enabled = currentPage < totalPages
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.pdf_next_page)
                        )
                    }
                }
            }
        }

        // ── 加载指示器 ──
        if (isLoading && !hasError) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        // ── 错误状态 ──
        if (hasError) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(SpacingTokens.XXL.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.pdf_load_failed),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(SpacingTokens.SM.dp))
                Text(
                    text = errorMessage.ifBlank { stringResource(R.string.unknown_error) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
