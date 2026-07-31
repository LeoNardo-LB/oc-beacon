package dev.leonardo.ocbeacon.ui.screens.viewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log

/**
 * One-time WebView V8 engine pre-warm.
 *
 * The first WebView creation in a process pays a heavy cost (~300-500ms) to
 * initialize the V8 JavaScript engine, load the HTML asset, and parse JS.
 * Subsequent WebViews skip the V8 init step entirely.
 *
 * This class creates a throwaway WebView that loads [code_viewer.html],
 * waits for [onPageFinished], then immediately destroys itself. The V8
 * engine state remains warm in the process, so the next CodeWebView created
 * (when the user opens a file) starts fast.
 *
 * Resource usage: ~5-10 MB temporarily (freed on destroy). No persistent
 * memory, no background threads, no view hierarchy attachment.
 *
 * Called once from ChatScreen's LaunchedEffect — by the time the AI finishes
 * generating tool cards and the user taps "open file", the engine is ready.
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

        // Safety net: destroy after timeout even if onPageFinished never fires
        val timeoutRunnable = Runnable {
            Log.w(TAG, "Warm-up timed out after ${TIMEOUT_MS}ms, destroying")
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
                        Log.d(TAG, "Warm-up complete, destroying throwaway WebView")
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
            Log.d(TAG, "Warm-up started: loading code_viewer.html")
        } catch (e: Exception) {
            Log.e(TAG, "Warm-up failed, allowing retry", e)
            warmed = false
            handler.removeCallbacks(timeoutRunnable)
            try {
                warmWebView?.destroy()
            } catch (_: Exception) {
            }
        }
    }
}
