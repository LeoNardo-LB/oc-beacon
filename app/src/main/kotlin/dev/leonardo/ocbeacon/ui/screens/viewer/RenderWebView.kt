package dev.leonardo.ocbeacon.ui.screens.viewer

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 统一的基于 WebView 的渲染器。复用单个 WebView 实例 — 切换只改变
 * [View.VISIBLE]/[View.GONE]，不销毁/重建。
 *
 * 支持：MARKDOWN（marked.js + highlight.js）、IMAGE（base64）、SVG、CSV。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RenderWebView(
    content: String,
    fileType: FileType,
    mimeType: String = "image/*",
    visible: Boolean = true,
    modifier: Modifier = Modifier
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val isDark = surfaceColor.red * 0.299f + surfaceColor.green * 0.587f + surfaceColor.blue * 0.114f < 0.5f
    val bgColorArgb = surfaceColor.toArgb()
    val bgHex = argbToHex(bgColorArgb)
    val fgHex = argbToHex(MaterialTheme.colorScheme.onSurface.toArgb())

    // 为 JS 模板字面量转义 markdown 内容
    val escapedContent = remember(content) {
        content.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")
    }

    // 为 IMAGE/SVG/CSV 预构建的 HTML（MARKDOWN 改用 asset 模板）
    val html = remember(content, fileType, mimeType, bgColorArgb) {
        when (fileType) {
            FileType.IMAGE -> buildImageHtml(content, mimeType, bgHex)
            FileType.SVG, FileType.CSV -> RenderHtmlBuilder.build(fileType, content, isDark, bgHex, fgHex)
            FileType.HTML -> content   // 原始 HTML 直接加载
            else -> ""
        }
    }

    val jsCommand = remember(escapedContent, isDark, bgHex, fgHex) {
        "renderMarkdown(`$escapedContent`, $isDark, '$bgHex', '$fgHex');"
    }

    // 跟踪上次应用的值，避免重组时不必要的重新加载
    var webViewRef: WebView? = null
    var lastHtml by remember { mutableStateOf("") }
    var lastJsCommand by remember { mutableStateOf("") }

    // composable 离开组合时清理 WebView
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                loadUrl("about:blank")
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            }
            webViewRef = null
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    if (fileType == FileType.MARKDOWN || fileType == FileType.HTML) {
                        javaScriptEnabled = true
                    }
                    if (fileType == FileType.IMAGE) {
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                    if (fileType == FileType.HTML) {
                        // 安全限制：禁止访问本地文件系统
                        allowFileAccess = false
                        allowContentAccess = false
                        domStorageEnabled = true   // 某些 HTML 需要 localStorage
                    }
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }
                setBackgroundColor(bgColorArgb)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (fileType == FileType.MARKDOWN) {
                            view?.evaluateJavascript(jsCommand, null)
                        }
                    }
                }
                if (fileType == FileType.MARKDOWN) {
                    loadUrl("file:///android_asset/markdown_viewer.html")
                    lastJsCommand = jsCommand
                } else {
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                    lastHtml = html
                }
                webViewRef = this
            }
        },
        update = { webView ->
            webView.visibility = if (visible) View.VISIBLE else View.GONE
            if (fileType == FileType.MARKDOWN) {
                if (jsCommand != lastJsCommand) {
                    lastJsCommand = jsCommand
                    webView.evaluateJavascript(jsCommand, null)
                }
            } else {
                if (html != lastHtml) {
                    lastHtml = html
                    webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
            }
        }
    )
}

private fun buildImageHtml(base64Data: String, mimeType: String, bgHex: String): String {
    return """
    <!DOCTYPE html>
    <html>
    <head>
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5">
    <style>
        body { margin:0; padding:12px 16px; background:$bgHex; display:flex; justify-content:center; align-items:center; min-height:100vh; }
        img { max-width:100%; height:auto; object-fit:contain; }
    </style>
    </head>
    <body>
    <img src="data:$mimeType;base64,$base64Data" alt="preview" />
    </body>
    </html>
    """.trimIndent()
}
