package dev.leonardo.ocbeacon.ui.screens.viewer

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

private const val TAG = "PdfViewer"

/**
 * PDF viewer using PDF.js in WebView.
 * Loads base64-encoded PDF data and renders pages to canvas.
 *
 * Key fix: `allowFileAccessFromFileURLs = true` enables pdf.js Web Worker
 * to load from `file://` protocol (required for rendering).
 *
 * @param base64Data Base64-encoded PDF content from API
 * @param visible Whether the viewer is visible
 * @param modifier Compose modifier
 */
@SuppressLint("SetJavaScriptEnabled")
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

    // Clean up WebView when composable leaves composition
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
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
                        // CRITICAL: Allow Web Worker to load from file:// protocol.
                        // Without this, pdf.js cannot create its worker and fails silently.
                        allowFileAccessFromFileURLs = true
                        allowUniversalAccessFromFileURLs = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        loadWithOverviewMode = true
                        useWideViewPort = true
                    }

                    // JS Interface for callbacks from pdf_viewer.html
                    // NOTE: @JavascriptInterface methods run on WebView's JavaBridge
                    // thread, NOT the main thread. Must post to main thread to safely
                    // modify Compose state.
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
                                Log.e(TAG, "PDF.js error: $message")
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
                            Log.d(TAG, "Page finished loading, injecting PDF data")
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
                            Log.e(TAG, "WebView error: ${error?.description}")
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            Log.d(TAG, "JS Console [${consoleMessage.messageLevel()}]: ${consoleMessage.message()}")
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

        // ── Toolbar overlay (page navigation) ──
        if (!isLoading && !hasError && totalPages > 0) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
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
                            contentDescription = "Previous page"
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
                            contentDescription = "Next page"
                        )
                    }
                }
            }
        }

        // ── Loading indicator ──
        if (isLoading && !hasError) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        // ── Error state ──
        if (hasError) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(SpacingTokens.XXL.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "PDF 加载失败",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(SpacingTokens.SM.dp))
                Text(
                    text = errorMessage.ifBlank { "未知错误" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
