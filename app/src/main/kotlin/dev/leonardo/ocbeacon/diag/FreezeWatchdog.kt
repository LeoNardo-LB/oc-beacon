package dev.leonardo.ocbeacon.diag

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #545 BLANK-RENDER-FREEZE 黑匣子探针（DEBUG 门控，repo [DEBUG-xxx] 探针文化）。
 *
 * **零扰动设计（2026-10-19 观测者效应实证教训）**：
 *   - v1 主动心跳（500ms 主线程 post）→ 竞态 0/6 不复现；
 *   - v2 被动 Choreographer 帧链 → 仍 0/8——帧链让每帧都有 pending callback，
 *     vsync 管线永不停，而冻结本质=帧停止，任何帧接触都破坏冻结条件；
 *   - v3（本版）**广播触发式**：无周期任务、无 Choreographer、无主线程接触——
 *     仅注册 receiver，由外部检测方在冻结后触发：
 *     adb shell am broadcast -a dev.ocbeacon.DEBUG_FREEZE_DUMP
 *     （冻结态主线程 epoll_pwait 空闲可响应消息——tap 派发实验已证，journal §8.2）
 *
 * 打印（logcat tag=FREEZE-WATCH）：主线程 Java 栈 + Activity lifecycle 态 +
 * content View 树（AndroidComposeView attach/子树深度——组合本体挂起的定向信号）。
 */
object FreezeWatchdog {

    private const val TAG = "FREEZE-WATCH"
    const val ACTION_DUMP = "dev.ocbeacon.DEBUG_FREEZE_DUMP"

    /**
     * #545 sentinel state（Recomposer 死活判别，journal §9.4）：
     * 广播 --ez bump true 写入；MainActivity 组合根的 snapshotFlow 观察者若打出
     * "sentinel reacted" = Recomposer 活（内容空但可重组→dispose 竞态路径）；
     * 无反应 = Recomposer 死（取消/挂起→协程域异常传播路径）。
     */
    val sentinel = androidx.compose.runtime.mutableStateOf(0)

    private val registered = AtomicBoolean(false)

    fun install(activity: Activity) {
        if (!registered.compareAndSet(false, true)) return
        try {
            // targetSdk 34+ 强制 exported 标志（2026-10-19 实测 SecurityException）；
            // EXPORTED：广播由外部 adb shell 发起（跨 uid），NOT_EXPORTED 会被拒收
            androidx.core.content.ContextCompat.registerReceiver(
                activity,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.getBooleanExtra("bump", false)) {
                            sentinel.value++
                            Log.e(TAG, "sentinel bumped to ${sentinel.value} — watch for 'sentinel reacted' (无 reacted=Recomposer 死)")
                        }
                        if (intent.getBooleanExtra("invalidate", false)) {
                            // 定罪实验：冻结态强行请求重绘——有新帧=帧链活着（内容真空）；
                            // 无新帧=Choreographer/traversal 链死
                            activity.findViewById<ViewGroup>(android.R.id.content)?.invalidate()
                            Log.e(TAG, "invalidate posted from broadcast")
                        }
                        report(activity)
                    }
                },
                IntentFilter(ACTION_DUMP),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
            Log.i(TAG, "installed (broadcast-triggered, zero-perturbation)")
        } catch (t: Throwable) {
            Log.w(TAG, "register failed: $t")
        }
    }

    private fun report(act: Activity) {
        val sb = StringBuilder()
        sb.appendLine("dump-on-demand:")
        // 主线程栈（onReceive 已在主线程；冻结态此处能到达=主线程消息通道活着）
        val mainStack = Thread.currentThread().stackTrace
        sb.appendLine("mainThread stack (${mainStack.size} frames):")
        mainStack.take(25).forEach { sb.appendLine("  at $it") }
        // 组合活性——Activity 即 LifecycleOwner（ComponentActivity）
        val actState = (act as? LifecycleOwner)?.lifecycle?.currentState
        sb.appendLine("activityLifecycle=$actState")
        sb.appendLine("hasWindowFocus=${act.hasWindowFocus()}")
        val content = act.findViewById<ViewGroup>(android.R.id.content)
        sb.appendLine("contentTree:")
        content?.let { dumpTree(it, 0, sb, maxDepth = 6) }
        val text = sb.toString()
        Log.e(TAG, text)
        try {
            dev.leonardo.ocbeacon.logging.AppLogger.e(TAG, text)
        } catch (_: Throwable) { /* 冻结现场 DB 写入可能不可用——logcat 已兜底 */ }
    }

    private fun dumpTree(v: View, depth: Int, sb: StringBuilder, maxDepth: Int) {
        if (depth > maxDepth) return
        val indent = "  ".repeat(depth + 1)
        sb.appendLine("${indent}${v.javaClass.simpleName} attached=${v.isAttachedToWindow} children=${if (v is ViewGroup) v.childCount else 0} w=${v.width} h=${v.height}")
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                dumpTree(v.getChildAt(i), depth + 1, sb, maxDepth)
            }
        }
    }
}
