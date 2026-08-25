package dev.leonardo.ocbeacon.ui.screens.chat.components

/**
 * 自动分页触发策略（C10，2026-08-26 架构走查候选 10）——纯函数，无 Compose 依赖。
 *
 * older/newer 两个方向「距边 ≤8 项即触发」的触发决策唯一真相源（原散在
 * ChatMessageList 两个 LaunchedEffect 各 ~60/37 行）。本文件只做决策：
 * 布局/分页/跳转状态输入 → [AutoLoadDecision] 输出；effect 桥（ChatMessageList）
 * 瘦身为 snapshotFlow → policy → delegate.load。
 *
 * ===== 历史修复语义存档（4 次，逐条保留——回归即重蹈） =====
 *
 * **2026-08-10（isScrollInProgress 依赖移除）**：触发不依赖滚动状态——用户滑到
 * 顶"停住"时 isScrollInProgress=false 导致不触发（"看似滑到顶但有更多内容"）。
 * 现语义：距顶 <=8 即触发（无论是否滚动中）；加载完成 isLoadingOlder 翻转 →
 * effect 重启（key 含 messageState 字段）→ 重新监听布局 → 若仍距顶近则自动续载。
 * 进入会话不触发：firstVisible=0（视觉底部），total-firstVisible 大。
 *
 * **2026-08-12（reverseLayout 索引方向）**：视觉顶部 = 可见项中 index 最大
 * （reverseLayout：最旧在最上、index 最大）——原实现用
 * visibleItemsInfo.firstOrNull()（index 最小 = 视觉底部），用户滑到顶部时底部项
 * index 仍远离 total → nearTop 永不满足 → 更旧消息永远加载不了（用户反馈
 * "滚动不上去了"，视口卡在 11:44——该处已是已加载最旧但 loadOlder 未触发）。
 *
 * **2026-08-21 ×2（fire-time 竞态，同日根因完备化）**：!jumpLockActive 只在
 * effect 启动时检查一次——跳转滚动使 nearTop/nearBottom 在旧实例 collect 里
 * 发射时（标志翻转与 effect 重启之间有重组延迟窗口——重组帧驱动、snapshotFlow
 * 发射提交驱动，二者排序无保证，跳转重载下窗口实测拉宽到 136ms+），启动闸门
 * 已失效 → settle 期间数据变动（真机日志实证：jumpToMessage 置锁后 +136ms
 * nearBottom 发射仍漏过启动闸门 → 渐进步进卡 gap=-343 空转 7 次、蒙版多挂
 * ~2s）。修复 = 正确的时机 × 正确的源：**fire-time 复查** + 直读 phase 真源
 * （isJumpInProgress 同步快照——不经派生锁的组合帧滞后）。
 *
 * 防风暴（退避）：autoLoadPaused（连续失败 3 次）→ 停止自动续载；fire 前查询
 * delegate 的退避等待（autoLoadWaitMillis）——失败后按 500ms 指数退避重试。
 */

/** 可见项纯数据快照（LazyListItemInfo 的桥接投影）。 */
data class VisibleItemSnapshot(
    val index: Int,
    val offset: Int,
    val size: Int,
)

/** LazyListLayoutInfo 的纯数据快照（桥接装配；visibleItems 按索引升序——Compose 契约）。 */
data class AutoLoadLayoutSnapshot(
    val totalItemsCount: Int,
    val visibleItems: List<VisibleItemSnapshot>,
    val viewportEndOffset: Int,
)

/** 分页状态快照。older 方向：hasOlderMessages/isLoadingOlder/autoLoadPaused；newer：hasNewerMessages/isLoadingNewer。 */
data class AutoLoadPagingState(
    val hasMore: Boolean,
    val isLoading: Boolean,
    val paused: Boolean = false,
)

/** 触发决策（older/newer 同构）。 */
sealed class AutoLoadDecision {
    /** 门控未通过（无更多/加载中/暂停[防风暴]/跳转锁）——effect 不订阅布局流。 */
    object Gated : AutoLoadDecision()

    /** 布局未达触发阈值——等待后续布局变化。 */
    object Wait : AutoLoadDecision()

    /** 达到阈值：先退避 [backoffMillis]（>0 时；0 = 无退避直通），fire 前复查跳转互斥。 */
    data class Trigger(val backoffMillis: Long) : AutoLoadDecision()
}

object AutoLoadPolicy {

    /** 距边阈值：距视觉顶/底 ≤8 项即触发（两方向对称）。 */
    const val NEAR_EDGE_ITEMS = 8

    // ===== 门控 =====

    /** effect 启动闸门：有更多 + 非加载中 + 未暂停 + 无跳转锁。
     *  注意（08-21 ×2）：这**不是**充分的跳转互斥——启动后布局发射到 fire 之间
     *  存在重组延迟窗口，fire-time 还须 [fireAllowed] 复查。 */
    fun startGate(paging: AutoLoadPagingState, jumpLocked: Boolean): Boolean =
        paging.hasMore && !paging.isLoading && !paging.paused && !jumpLocked

    /** fire-time 跳转互斥复查（2026-08-21 竞态修复）：直读 phase 真源
     *  （isJumpInProgress 同步快照——不经派生锁的组合帧滞后）。 */
    fun fireAllowed(jumpInProgressAtFire: Boolean): Boolean = !jumpInProgressAtFire

    /** collect 体入口的触发装配：退避等待值由 delegate 运行态注入
     *  （autoLoadWaitMillis——失败后 500ms 指数退避；0 = 无退避直通）。 */
    fun trigger(backoffWaitMillis: Long): AutoLoadDecision.Trigger =
        AutoLoadDecision.Trigger(backoffMillis = backoffWaitMillis)

    // ===== older 方向（视觉顶部 = 更旧消息） =====

    /** older 阈值：距视觉顶 ≤8 项，或内容不足一屏（见类注释 08-10/08-12 存档）。 */
    fun olderThresholdMet(layout: AutoLoadLayoutSnapshot): Boolean {
        // 08-12：视觉顶部 = 可见项中 index 最大（reverseLayout：最旧在最上）。
        val topMost = layout.visibleItems.maxByOrNull { it.index }
        val nearTop = layout.totalItemsCount - (topMost?.index ?: 0) <= NEAR_EDGE_ITEMS
        // 内容不足一屏（最顶可见项未填满视口）时也触发：主会话初始加载经
        // displayItems 过滤后可能仅剩 13 条——不足一屏时用户无法滚动
        // （firstVisible 恒 0），永达不到 nearTop → 历史加载静默失效
        // （用户反馈"向上滑动加载历史消息也没有"）。
        val contentDoesNotFillViewport = topMost == null ||
            topMost.offset + topMost.size < layout.viewportEndOffset
        return nearTop || contentDoesNotFillViewport
    }

    /** older 全决策（表驱动测试入口；桥接拆用 startGate/olderThresholdMet/trigger）。 */
    fun olderDecision(
        layout: AutoLoadLayoutSnapshot,
        paging: AutoLoadPagingState,
        jumpLocked: Boolean,
        backoffWaitMillis: Long = 0L,
    ): AutoLoadDecision = when {
        !startGate(paging, jumpLocked) -> AutoLoadDecision.Gated
        !olderThresholdMet(layout) -> AutoLoadDecision.Wait
        else -> AutoLoadDecision.Trigger(backoffMillis = backoffWaitMillis)
    }

    /** 触发条件附近的低频诊断（未近阈值/total=0 → null——滚动高频段不刷屏）。 */
    fun olderProbeReason(layout: AutoLoadLayoutSnapshot): String? {
        if (layout.totalItemsCount <= 0 || !olderThresholdMet(layout)) return null
        val topVisible = layout.visibleItems.maxOfOrNull { it.index } ?: 0
        val nearTop = layout.totalItemsCount - topVisible <= NEAR_EDGE_ITEMS
        return "auto-load probe: topVisible=" + topVisible + " total=" + layout.totalItemsCount +
            " nearTop=" + nearTop + " fillsViewport=" + fillsViewport(layout)
    }

    // ===== newer 方向（视觉底部 = 更新消息，仅 loadAround 定位后激活） =====

    /** newer 阈值：firstVisible（可见项最低索引）≤8 = 视觉底部（更新方向）。
     *  与 older 的 total - topVisible ≤ 8（视觉顶部）对称。无更多更新数据时
     *  服务器返回不足一页 → FSM 置 hasNewer=false → 停止。 */
    fun newerThresholdMet(layout: AutoLoadLayoutSnapshot): Boolean =
        (layout.visibleItems.minOfOrNull { it.index } ?: 0) <= NEAR_EDGE_ITEMS

    /** newer 全决策（无退避——newer 方向无失败风暴路径）。 */
    fun newerDecision(
        layout: AutoLoadLayoutSnapshot,
        paging: AutoLoadPagingState,
        jumpLocked: Boolean,
    ): AutoLoadDecision = when {
        !startGate(paging, jumpLocked) -> AutoLoadDecision.Gated
        !newerThresholdMet(layout) -> AutoLoadDecision.Wait
        else -> AutoLoadDecision.Trigger(backoffMillis = 0L)
    }

    /** newer 低频诊断（firstVisible ≤12 时打印——原探针阈值）。 */
    fun newerProbeReason(layout: AutoLoadLayoutSnapshot): String? {
        val firstVisible = layout.visibleItems.minOfOrNull { it.index } ?: 0
        if (firstVisible > 12) return null
        return "nearBottom probe: firstVisible=" + firstVisible
    }

    private fun fillsViewport(layout: AutoLoadLayoutSnapshot): Boolean {
        val topMost = layout.visibleItems.maxByOrNull { it.index } ?: return false
        return topMost.offset + topMost.size >= layout.viewportEndOffset
    }
}
