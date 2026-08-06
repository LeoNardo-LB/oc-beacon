package dev.leonardo.ocbeacon.ui.screens.viewer

import dev.leonardo.ocbeacon.logging.AppLogger

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 一次性 WebView V8 引擎预热。
 *
 * 进程内首次创建 WebView 需要付出较大开销（约 300-500ms）来
 * 初始化 V8 JavaScript 引擎、加载 HTML asset 并解析 JS。
 * 后续 WebView 会完全跳过 V8 初始化步骤。
 *
 * 本类创建一个一次性 WebView，加载 [code_viewer.html]，
 * 等待 [onPageFinished]，然后立刻销毁自身。V8 引擎状态在进程中
 * 保持热状态，因此下一次（用户打开文件时）创建的 CodeWebView 启动很快。
 *
 * 资源占用：临时约 5-10 MB（销毁时释放）。无持久内存、
 * 无后台线程、不附加到视图层级。
 *
 * 从 ChatScreen 的 LaunchedEffect 中调用一次 — 等 AI 生成完工具卡片、
 * 用户点击"打开文件"时，引擎已就绪。
 */
object WebViewWarmer {

    private const val TAG = "WebViewWarmer"
    private const val TIMEOUT_MS = 5_000L

    @Volatile
    private var warmed = false

    fun warm(context: Context) {
        if (warmed) return
        warmed = true

        val handler = Handler(Looper.getMainLooper())

        var warmWebView: WebView? = null

        // 安全网：即使 onPageFinished 一直不触发，也在超时后销毁
        val timeoutRunnable = Runnable {
            AppLogger.w(TAG, "Warm-up timed out after ${TIMEOUT_MS}ms, destroying")
            try {
                warmWebView?.loadUrl("about:blank")
                warmWebView?.destroy()
            } catch (_: Exception) {
            }
            warmWebView = null
        }

        try {
            warmWebView = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        handler.removeCallbacks(timeoutRunnable)
                        AppLogger.d(TAG, "Warm-up complete, destroying throwaway WebView")
                        view?.post {
                            try {
                                view.loadUrl("about:blank")
                                view.destroy()
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }

            handler.postDelayed(timeoutRunnable, TIMEOUT_MS)

            val html = context.assets.open("code_viewer.html").bufferedReader().use { it.readText() }
            val wv = warmWebView ?: throw IllegalStateException("WebView creation failed")
            wv.loadDataWithBaseURL(
                "file:///android_asset/", html, "text/html", "UTF-8", null
            )
            AppLogger.d(TAG, "Warm-up started: loading code_viewer.html")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Warm-up failed, allowing retry", e)
            warmed = false
            handler.removeCallbacks(timeoutRunnable)
            try {
                warmWebView?.destroy()
            } catch (_: Exception) {
            }
        }
    }
}
