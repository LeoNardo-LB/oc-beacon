package dev.leonardo.ocbeacon.ui.screens.chat.scroll

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import java.lang.ref.WeakReference

/**
 * FLUSH 相任务(#423 批次二,spec §2/K1):绘制前单点排干。
 *
 * @return true=允许本帧绘制;**false=残差未闭,拒绘并请求本帧重排**——
 * 「增长已落地但配对未完成」的中间态由此从构造上不可能上屏(Chromium
 * dual-phase 同款语义;拒绘次数由任务方自限 ≤2/帧,防无限重排,spec §8)。
 */
internal fun interface PreDrawFlushTask {
    fun onPreDraw(): Boolean
}

/**
 * #432 PreRenderCoordinator — 渲染前计算/协调模块(Phase 1:视口租约)。
 *
 * 单一职责:列表高度变化 → 视口滚动配对 的仲裁权威。设计 spec 见
 * docs/specs/2026-09-21-pre-render-coordinator-design.md。
 *
 * Phase 1 落地范围:
 * - **视口租约(I3,已接线)**:任何渲染前事务(卡片展开/收起动画)激活期间,
 *   ChatScrollController 的守卫重锚/MSGEFFECT 锚底/PENDING 拉底/强滚锚底
 *   (有界等待)与 ChatMessageList 横幅锚底一律让位。十一轮收口实证:守卫与
 *   episode 位移指令通过共享视口互搏(±H 战争,展开收起双向均受害)——租约
 *   从构造上消除该竞态类。
 * - **事务注册(已接线)**:CardExpandReveal episode 全程注册(withEpisode,
 *   含 finally 收尾路径),激活计数即租约来源;后续大组硬切换(registerSwap)/
 *   SSE 流式(registerStreaming)按 spec Phase 2/3 接入。
 * - 单测:PreRenderCoordinatorTest(计数/异常/取消释放)+
 *   AutoScrollArbiterTest(让位复查)+ ChatScrollControllerTest(强滚有界等待)。
 *
 * 单例说明:应用同一时刻仅一个会话列表活跃,单例即每列表语义;spec 备注了
 * 多列表场景的组合局部化改造。
 *
 * 批次二增补——FLUSH 相(spec §2 帧管线第三相):宿主视图(ChatMessageList
 * 经 LocalView 挂接)上**单点** OnPreDrawListener,注册序逐任务排干;任一
 * 任务拒绘则本帧拒绘。无宿主/无任务时零监听零开销;降级路径=组件回退至
 * onGloballyPositioned+帧兜底(批次二前行为)。
 *
 * 线程语义:全部状态仅在主线程(Compose UI/其 effect 协程)读写——单写者
 * 前提由调度器保证,计数操作无需原子原语。
 */
object PreRenderCoordinator {

    /**
     * FLUSH 相总开关(批次二 A/B 取证):false → 宿主不挂接,引擎自动降级回
     * placed+stab 路径(批次二前行为),观测探针不受影响。定案后恒 true。
     */
    internal const val FLUSH_PHASE_ENABLED = true

    /**
     * 活动渲染前事务数(>0 = 视口租约被持有),只读暴露——计数不变量
     * (计数=withEpisode 嵌套深度)由 withEpisode 独占维护,外部不可直改。
     * backing 为快照 int state:让位方在 snapshotFlow/去抖复查中读它即订阅生效。
     */
    val activeCount: Int get() = backing.intValue

    private val backing = mutableIntStateOf(0)

    /** 守卫/锚底/拉底让位判定:存在活动事务时禁止任何自动视口操作。 */
    val hasActiveTransactions: Boolean get() = backing.intValue > 0

    /**
     * 渲染前事务作用域:episode 全程(含 settle/A/B/收尾)持有视口租约。
     * try/finally 保证取消/异常路径必释放——租约泄漏=守卫永久哑火,故
     * 释放放在 finally 且不依赖事务正常完成。
     */
    suspend fun <T> withEpisode(block: suspend () -> T): T {
        // 读-改-写依赖 Compose UI 单主线程约定(effect 协程均派发于主线程),
        // 无并发写者——线程语义见类 KDoc。
        backing.intValue = backing.intValue + 1
        try {
            return block()
        } finally {
            backing.intValue = backing.intValue - 1
        }
    }

    // ===== FLUSH 相:单点 OnPreDraw(批次二,#423) =====

    /** FLUSH 任务表(注册序即执行序;仅主线程)。 */
    private val flushTasks = mutableListOf<PreDrawFlushTask>()

    private var hostRef: WeakReference<View>? = null

    private var listenerAttached = false

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener { runFlush() }

    /** FLUSH 宿主已挂接(组件据此选择 flush 路径 or 降级路径)。 */
    val isFlushHostAttached: Boolean get() = hostRef?.get() != null

    /** 宿主挂接(ChatMessageList 组合期提供;列表根即整窗绘制前单点)。 */
    fun attachFlushHost(view: View) {
        if (!FLUSH_PHASE_ENABLED) return
        if (hostRef?.get() === view) return
        if (listenerAttached) detachFlushListener()
        hostRef = WeakReference(view)
        syncListener()
    }

    /** 宿主摘除(组合销毁;与 attach 严格配对)。 */
    fun detachFlushHost(view: View) {
        if (hostRef?.get() !== view) return
        detachFlushListener()
        hostRef = null
    }

    /** 注册 FLUSH 任务;首个任务到达时挂监听(空闲零开销)。 */
    internal fun registerFlushTask(task: PreDrawFlushTask) {
        flushTasks.add(task)
        syncListener()
    }

    internal fun unregisterFlushTask(task: PreDrawFlushTask) {
        flushTasks.remove(task)
        if (flushTasks.isEmpty()) detachFlushListener()
    }

    /**
     * 单点排空:按注册序逐任务执行,任一拒绘(false)→本帧拒绘。
     * 迭代副本——任务可在回调内注销(重入安全)。JVM 可直测。
     */
    internal fun runFlush(): Boolean {
        var allowDraw = true
        for (task in flushTasks.toList()) {
            if (!task.onPreDraw()) allowDraw = false
        }
        return allowDraw
    }

    private fun syncListener() {
        val host = hostRef?.get() ?: return
        if (flushTasks.isNotEmpty() && !listenerAttached) {
            host.viewTreeObserver.addOnPreDrawListener(preDrawListener)
            listenerAttached = true
        }
    }

    private fun detachFlushListener() {
        if (!listenerAttached) return
        hostRef?.get()?.viewTreeObserver?.removeOnPreDrawListener(preDrawListener)
        listenerAttached = false
    }
}
