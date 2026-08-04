package dev.leonardo.ocbeacon.ui.screens.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.util.DebugLogger

private const val TAG = "CodeWebView"

private fun extToLanguage(filePath: String): String {
    val ext = filePath.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "kts" -> "kotlin"; "java" -> "java"; "xml" -> "xml"
        "json" -> "json"; "py" -> "python"; "js" -> "javascript"
        "ts" -> "typescript"; "go" -> "go"; "rs" -> "rust"
        "c", "h" -> "c"; "cpp", "cc", "cxx" -> "cpp"; "cs" -> "csharp"
        "rb" -> "ruby"; "swift" -> "swift"; "php" -> "php"
        "sh", "bash" -> "bash"; "sql" -> "sql"; "yaml", "yml" -> "yaml"
        "html", "htm" -> "xml"; "css" -> "css"; "md" -> "markdown"
        "gradle" -> "groovy"; "properties" -> "properties"
        "dockerfile" -> "dockerfile"; "toml" -> "ini"; else -> ""
    }
}

private class SelectionBridge {
    private val mainHandler = Handler(Looper.getMainLooper())
    var callback: ((text: String, start: Int, end: Int) -> Unit)? = null
    var annotationClickCallback: ((id: String) -> Unit)? = null

    @JavascriptInterface
    fun onSelection(text: String, start: Int) {
        val end = start + text.length
        DebugLogger.log(TAG, "Bridge.onSelection: '${text.take(40)}' [$start-$end]")
        mainHandler.post { callback?.invoke(text, start, end) }
    }

    @JavascriptInterface
    fun onAnnotationClick(id: String) {
        DebugLogger.log(TAG, "Bridge.onAnnotationClick: id=$id")
        mainHandler.post { annotationClickCallback?.invoke(id) }
    }
}

/**
 * 把"批注"注入到原生文本选择 ActionMode 工具栏（与 复制 / 全选 并列）的
 * WebView 子类。
 *
 * 点击通过匹配 item 标题（而非 itemId）处理，因为 Android WebView 的
 * 内部 ActionMode 可能重新分配或不派发自定义 itemId。
 */
private class AnnotateWebView(
    context: Context,
    private val annotateLabel: String,
    private val bridge: SelectionBridge,
    private val onLoadMore: (() -> Unit)? = null,
) : WebView(context) {

    private val loadMoreRunnable = Runnable { onLoadMore?.invoke() }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (onLoadMore == null) return
        val contentH = contentHeight
        // 距底部 300px 以内时触发加载更多
        if (contentH > 0 && t + height >= contentH - 300) {
            removeCallbacks(loadMoreRunnable)
            postDelayed(loadMoreRunnable, 400)
        }
    }

    fun cleanup() {
        removeCallbacks(loadMoreRunnable)
    }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode {
        android.util.Log.e("ActionModeDebug", "startActionMode type=$type (0=FLOATING,1=PRIMARY)")
        if (callback == null) return super.startActionMode(null, type)

        val wrapped = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val ok = callback.onCreateActionMode(mode, menu)
                menu.add(Menu.NONE, Menu.NONE, 200, annotateLabel)
                return ok
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) =
                callback.onPrepareActionMode(mode, menu)

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.title?.toString() == annotateLabel) {
                    evaluateJavascript("getSelectionInfo()") { result ->
                        try {
                            val raw = result?.trim() ?: "null"
                            val tokener = org.json.JSONTokener(raw)
                            val parsed = tokener.nextValue()
                            val arr = when (parsed) {
                                is org.json.JSONArray -> parsed
                                is String -> org.json.JSONArray(parsed)
                                else -> throw org.json.JSONException("unexpected")
                            }
                            val text = arr.optString(0, "")
                            val start = arr.optInt(1, -1)
                            if (text.isNotBlank() && start >= 0) {
                                Handler(Looper.getMainLooper()).post {
                                    bridge.onSelection(text, start)
                                    mode.finish()
                                }
                            } else {
                                Handler(Looper.getMainLooper()).post { mode.finish() }
                            }
                        } catch (e: Exception) {
                            Handler(Looper.getMainLooper()).post { mode.finish() }
                        }
                    }
                    return true
                }
                return callback.onActionItemClicked(mode, item)
            }

            override fun onDestroyActionMode(mode: ActionMode) =
                callback.onDestroyActionMode(mode)
        }
        return super.startActionMode(wrapped, type)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CodeWebView(
    content: String,
    filePath: String,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onAnnotate: ((text: String, startOffset: Int, endOffset: Int) -> Unit)? = null,
    annotationsJson: String = "",
    onLoadMore: (() -> Unit)? = null,
    onAnnotationClick: ((id: String) -> Unit)? = null,
    initialScrollLine: Int = -1,
) {
    val annotateLabel = stringResource(R.string.annotation_context_annotate)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val isDark = surfaceColor.red * 0.299f + surfaceColor.green * 0.587f + surfaceColor.blue * 0.114f < 0.5f
    val bgColorArgb = surfaceColor.toArgb()
    val bgHex = argbToHex(bgColorArgb)
    val textHex = argbToHex(MaterialTheme.colorScheme.onSurface.toArgb())
    val language = remember(filePath) { extToLanguage(filePath) }
    val escapedContent = remember(content) {
        content.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")
    }
    // 转义 JSON 中的单引号和反斜杠，避免破坏 JS 字符串字面量
    val safeAnnotationsJson = remember(annotationsJson) {
        annotationsJson.replace("\\", "\\\\").replace("'", "\\'")
    }

    // rememberUpdatedState：factory 的 onPageFinished 通过读取这些 .value 引用
    // 始终拿到最新内容，而非陈旧的 factory 闭包捕获值。
    // 没有这一步，onPageFinished 会应用首次组合时的内容，
    // 若 ViewModel 在加载中途更新，内容可能与当前内容不一致。
    val currentEscaped = rememberUpdatedState(escapedContent)
    val currentLang = rememberUpdatedState(language)
    val currentDark = rememberUpdatedState(isDark)
    val currentJson = rememberUpdatedState(safeAnnotationsJson)
    val currentScroll = rememberUpdatedState(initialScrollLine)

    val bridge = remember { SelectionBridge() }
    bridge.callback = onAnnotate
    bridge.annotationClickCallback = onAnnotationClick

    var webViewRef: AnnotateWebView? = null
    // 跟踪上次应用的值，避免滚动时不必要的 DOM 重建
    var lastEscaped by remember { mutableStateOf("") }
    var lastIsDark by remember { mutableStateOf(!isDark) }
    var lastJson by remember { mutableStateOf("") }
    var pageLoaded by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                cleanup()
                stopLoading()
                removeJavascriptInterface("AndroidBridge")
                loadUrl("about:blank")
                clearHistory()
                (parent as? android.view.ViewGroup)?.removeView(this)
                destroy()
            }
            webViewRef = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            AnnotateWebView(ctx, annotateLabel, bridge, onLoadMore).apply {
                settings.javaScriptEnabled = true
                settings.userAgentString = "OCBeaconCodeViewer"
                settings.allowFileAccess = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = false
                setBackgroundColor(bgColorArgb)
                addJavascriptInterface(bridge, "AndroidBridge")

                // 捕获 JS console.log → DebugLogger，用于诊断批注点击
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                        DebugLogger.log(TAG, "JS: ${consoleMessage.message()}")
                        return true
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageLoaded = true
                        // 使用 rememberUpdatedState 引用 — 即使 ViewModel
                        // 在 factory 创建到现在之间更新过，也保证拿到最新内容。
                        val ec = currentEscaped.value
                        val lang = currentLang.value
                        val dark = currentDark.value
                        val json = currentJson.value
                        val scroll = currentScroll.value
                        view?.evaluateJavascript(
                            "setCode(`$ec`, '$lang'); setTheme($dark, '$bgHex', '$textHex');",
                            null
                        )
                        if (scroll > 0) {
                            view?.evaluateJavascript("scrollToLine($scroll);", null)
                        }
                        if (json.isNotBlank() && json != "[]") {
                            view?.evaluateJavascript("applyAnnotations('$json');", null)
                        }
                        // 同步跟踪变量，避免下次 update() 重复应用
                        lastEscaped = ec
                        lastIsDark = dark
                        lastJson = json
                    }
                }

                val html = ctx.assets.open("code_viewer.html").bufferedReader().use { it.readText() }
                    .replace("__ANNOTATE_LABEL__", annotateLabel)
                    .replace("__COPY_LABEL__", "Copy")
                loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
                webViewRef = this
            }
        },
        update = { webView ->
            webView.visibility = if (visible) View.VISIBLE else View.GONE
            webView.post {
                // 在 HTML 页面加载完成（onPageFinished）之前跳过 JS 调用。
                // 此前 setCode/setTheme 不存在 → evaluateJavascript
                // 会静默失败。onPageFinished 负责首次内容应用。
                if (!pageLoaded) return@post

                // 仅当内容确实变化时才更新 WebView — 避免
                // 滚动时重建 DOM（曾导致滚动跳动/闪烁）
                if (escapedContent != lastEscaped) {
                    lastEscaped = escapedContent
                    webView.evaluateJavascript(
                        "if(typeof setCodePreserveScroll==='function'){setCodePreserveScroll(`$escapedContent`, '$language');}else{setCode(`$escapedContent`, '$language');}",
                        null
                    )
                }
                if (isDark != lastIsDark) {
                    lastIsDark = isDark
                    webView.evaluateJavascript("setTheme($isDark, '$bgHex', '$textHex');", null)
                }
                if (safeAnnotationsJson != lastJson) {
                    lastJson = safeAnnotationsJson
                    if (safeAnnotationsJson.isNotBlank() && safeAnnotationsJson != "[]") {
                        webView.evaluateJavascript("applyAnnotations('$safeAnnotationsJson');", null)
                    }
                }
            }
        }
    )
}

internal fun argbToHex(argb: Int): String {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return String.format("#%02X%02X%02X", r, g, b)
}
