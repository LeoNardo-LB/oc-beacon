package dev.leonardo.ocbeacon.ui.screens.chat.scroll

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf

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
 * 线程语义:全部状态仅在主线程(Compose UI/其 effect 协程)读写——单写者
 * 前提由调度器保证,计数操作无需原子原语。
 */
object PreRenderCoordinator {

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
}
