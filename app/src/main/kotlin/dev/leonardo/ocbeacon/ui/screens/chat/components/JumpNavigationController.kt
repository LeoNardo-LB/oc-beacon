package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 跳转定位状态机（架构根治 2026-08-13——架构评审候选①+②+③）。
 *
 * 根因（"重复乱跳"）：旧流程"移动目标"（视口中部→顶部两次定位）→ 目标滚出
 * 视口 → LazyColumn 回收 → MarkdownState 重建 → 重新解析（214）→ 稳定（331）
 * → 收敛修正又移动 → 振荡循环。
 *
 * 根治：**一次定位到最终位置**——目标进入视口后不再移动（不回收、不重建、
 * 不重测），测量/收敛都在目标位置进行；收敛只做小位移。
 *
 * 状态机（决策可单测）：
 *   Idle → Preparing(蒙版: 预解析+估算定位) → Measuring(透明: 测量+列表同步)
 *        → Settling(收敛修正) → Displayed(显示+稳定窗口 900ms) / Failed(超时)
 *
 * 三窗口勿混淆（2026-08-20 C-R2/D-4 起）：①本滚动稳定窗口=900ms（Displayed 后
 * gap 静默修正，见 measureAndSettle 尾部 while 循环）；②jumpLock 解锁缓冲=
 * [JUMP_UNLOCK_DELAY_MS]=300ms；③渲染供给层分片冻结=跳转终点后 2s
 * （RenderSupplyCoordinator F3 门控——「终点+2s 内不提交」）。
 *
 * UI 派生（单一真相源——蒙版/门控不再各自为政）：
 *   - showMask = Preparing || Measuring || Settling
 *   - gateOpen = Displayed || Failed
 *   - jumpLockActive = 异步跳转窗口(markJumpPending) ∪ 进行中 ∪ 终点后 300ms 缓冲
 *     （#159 收口 2026-08-22：替代 ChatMessageList 手工镜像——原 4 写点任一
 *     遗漏即竞态，loadAround 失败路径漏复位已实证锁永久卡死）
 */
sealed interface JumpPhase {
    data object Idle : JumpPhase
    data class Preparing(val msgId: String) : JumpPhase
    data class Measuring(val msgId: String) : JumpPhase
    data class Settling(val msgId: String) : JumpPhase
    data class Displayed(val msgId: String) : JumpPhase
    data class Failed(val msgId: String, val reason: String) : JumpPhase
}

/** 状态转移事件（纯逻辑单测入口）。 */
internal sealed interface JumpEvent {
    data object PrepareStarted : JumpEvent
    data object ParsedReady : JumpEvent          // 预解析完成
    data object MeasureReady : JumpEvent         // 列表尺寸稳定（Ready + 同步）
    data object Settled : JumpEvent              // 收敛完成
    data class TimedOut(val stage: String) : JumpEvent
    data object Abort : JumpEvent
}

// ============ 纯函数（单测目标——本会话反复出错的计算） ============

/** 终点（Displayed/Failed）后 autoLoad 解锁缓冲（稳定窗口保护）。 */
internal const val JUMP_UNLOCK_DELAY_MS = 300L

/** 目标底边距视口底部的目标偏移：顶边贴视口顶（含 contentPaddingTop 修正）。 */
internal fun computeDesiredOffset(viewportHeight: Float, itemHeight: Float, contentPaddingTop: Float): Float =
    viewportHeight - itemHeight - contentPaddingTop

/** 顶边偏差：0 = 贴视口顶；正 = 超出（顶边在视口上方）。 */
internal fun computeGap(itemOffset: Int, itemSize: Int, viewportHeight: Float, contentPaddingTop: Float): Float =
    itemOffset + itemSize - (viewportHeight - contentPaddingTop)

/**
 * 2026-08-20 分片适配：查找跳转目标 item（纯函数，单测目标）。
 *
 * 根因：状态机两处循环用 `it.key == targetKey(msgId)` 精确匹配——assistant
 * turn 分片后 key 为 "t_<msgId>#c<i>"，精确匹配必然落空 → item=null →
 * 300ms 重定位循环空转 → 5s 超时 Failed（任务定位到分片消息必失败）。
 *
 * 修复：精确匹配优先（user 目标 / 未分片 turn）；落空时前缀匹配取
 * **最小 index** 的 chunk——首 chunk 顶边 = 消息顶边（含标签栏），
 * gap 对齐语义与整消息完全一致。
 */
internal fun findJumpTargetItem(
    visible: List<LazyListItemInfo>,
    targetKey: String,
): LazyListItemInfo? {
    visible.firstOrNull { it.key == targetKey }?.let { return it }
    val prefix = targetKey + "#c"
    return visible
        .filter { it.key is String && (it.key as String).startsWith(prefix) }
        .minByOrNull { it.index }
}

/** 状态转移（纯函数——事件驱动）。 */
internal fun jumpTransition(current: JumpPhase, event: JumpEvent): JumpPhase = when (event) {
    is JumpEvent.PrepareStarted -> when (current) {
        is JumpPhase.Idle -> JumpPhase.Preparing(currentMsgIdOf(current))
        else -> current
    }
    is JumpEvent.ParsedReady -> when (current) {
        is JumpPhase.Preparing -> JumpPhase.Measuring(current.msgId)
        else -> current
    }
    is JumpEvent.MeasureReady -> when (current) {
        is JumpPhase.Measuring -> JumpPhase.Settling(current.msgId)
        else -> current
    }
    is JumpEvent.Settled -> when (current) {
        is JumpPhase.Settling -> JumpPhase.Displayed(current.msgId)
        else -> current
    }
    is JumpEvent.TimedOut -> when (current) {
        is JumpPhase.Preparing -> JumpPhase.Failed(current.msgId, "预解析超时(${event.stage})")
        is JumpPhase.Measuring -> JumpPhase.Failed(current.msgId, "测量超时(${event.stage})")
        is JumpPhase.Settling -> JumpPhase.Failed(current.msgId, "收敛超时(${event.stage})")
        else -> current
    }
    is JumpEvent.Abort -> JumpPhase.Idle
}

private fun currentMsgIdOf(current: JumpPhase): String = when (current) {
    is JumpPhase.Preparing -> current.msgId
    is JumpPhase.Measuring -> current.msgId
    is JumpPhase.Settling -> current.msgId
    is JumpPhase.Displayed -> current.msgId
    is JumpPhase.Failed -> current.msgId
    else -> ""
}

// ============ 控制器（状态机 + 执行器） ============

/** 组合中访问跳转状态机（ChatMessageList 提供）。 */
val LocalJumpController = androidx.compose.runtime.staticCompositionLocalOf<JumpNavigationController> {
    error("LocalJumpController not provided")
}

/**
 * 跳转定位控制器——状态机编排（决策）+ 执行器（滚动/收敛细节）。
 * 状态转移纯逻辑可单测；执行器（requestScroll 取反/scrollBy/await）模拟器回归。
 */
class JumpNavigationController(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    /** 2026-08-21 渲染供给抽出（架构评审候选 1）：相位流可注入——与
     * RenderSupplyCoordinator 共享同一实例（其提交门控直读 phase.value）。
     * 默认自建，既有调用点行为不变。 */
    phaseFlow: MutableStateFlow<JumpPhase> = MutableStateFlow(JumpPhase.Idle),
    /** 2026-08-13：按 msgId 解析最新 lazy index（displayItems 变化后旧 index 失效——
     * SSE 插入新消息会改变目标 index——轮询 item=null 时重定位用）。 */
    private val resolveLazyIndex: (String) -> Int?,
) {
    /** 2026-08-13 根治"定位到回复"：目标 key 前缀——user 目标只匹配 "u_"，
     * assistant 目标（onLocateTask）只匹配 "t_"——t_/u_ 同 id 时不再歧义
     *（旧逻辑 `u_ || t_` firstOrNull 会匹配到同 id 的 assistant turn）。 */
    private var targetKeyPrefix: String = "u"

    private fun targetKey(msgId: String): String = "${targetKeyPrefix}_$msgId"
    private val _phase = phaseFlow
    val phase: StateFlow<JumpPhase> = _phase

    /**
     * UI 派生：autoLoad 启动门控锁（#159 收口 2026-08-22——替代
     * ChatMessageList.jumpLockActive 手工镜像）。
     *
     * 锁定窗口 = 异步跳转窗口（[markJumpPending]——目标未加载、phase 仍
     * Idle，但 loadAround 期间 nearTop 补载不得启动）∪ 跳转进行中
     * （Preparing/Measuring/Settling）∪ 终点缓冲（Displayed/Failed 后
     * [JUMP_UNLOCK_DELAY_MS]——稳定窗口内不放行，语义等价原 ChatMessageList
     * 解锁 effect 的 delay(300)）。
     *
     * 派生实现：phase.collectLatest——非终态置 true；终态保持 true 并延迟
     * 解锁（缓冲期内新跳转到来则取消延迟、继续锁定，等价原 collectLatest
     * 键重启语义）。异步窗口由 markJumpPending 同步直写（phase 无发射）。
     */
    private val _jumpLock = MutableStateFlow(false)
    val jumpLockActive: StateFlow<Boolean> = _jumpLock

    init {
        scope.launch {
            _phase.collectLatest { ph ->
                if (ph is JumpPhase.Displayed || ph is JumpPhase.Failed) {
                    _jumpLock.value = true
                    kotlinx.coroutines.delay(JUMP_UNLOCK_DELAY_MS)
                    _jumpLock.value = false
                    if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: 状态机终点——autoLoad 解锁")
                } else {
                    _jumpLock.value = ph !is JumpPhase.Idle
                }
            }
        }
    }

    /**
     * 异步跳转窗口标记（jumpToMessage 目标未加载分支入口）。成功路径由
     * 后续 jumpTo 置 Preparing 接管（锁继续为 true，无缝）；失败路径调
     * [clearPendingJumpLock] 解锁。
     */
    fun markJumpPending() {
        _jumpLock.value = true
    }

    /**
     * 异步跳转失败解锁（loadAround 两轮未命中）。phase 仍 Idle 时生效；
     * 若失败清理与活跃跳转交错（用户已点另一可跳目标），phase 非Idle——
     * 锁归进行中的跳转所有，本调用为 no-op。
     */
    fun clearPendingJumpLock() {
        if (_phase.value is JumpPhase.Idle) _jumpLock.value = false
    }

    // ============ 2026-08-20 A-F1/D-1 竞态根治：Job 管理 + 代际令牌 ============
    // 旧实现 jumpTo/jumpToTask 各自 scope.launch 且无取消——快速连跳时旧
    // measureAndSettle（含 Displayed 后 1.5s 稳定窗口的每 150ms scrollBy 修正）
    // 继续运行：两个位置写者互搏（终停位置=最后写者，可停在中段 chunk）+
    // targetKeyPrefix 共享可变（旧协程查错 key）+ 旧协程 TimedOut 写穿新跳转
    // 的 Preparing/Measuring 成 Failed（蒙版提前消失 + 门控时间戳被骗开）。
    // 修复：新跳转取消旧 Job；协程内用 isActive 防写穿（取消即放弃写 phase）。
    private var activeJob: kotlinx.coroutines.Job? = null

    /** 发起新代际：取消旧跳转协程（含稳定窗口）。 */
    private fun cancelPreviousJump() {
        activeJob?.cancel()
        activeJob = null
    }

    /** 当前跳转目标 msgId（MessageCardUser 门控判断用）。 */
    var currentTargetMsgId: String? = null
        private set

    /**
     * UI 派生：跳转进行中（Preparing/Measuring/Settling）。
     *
     * 2026-08-21 根因完备化：自动分页 fire-time 门控读 phase 真源（本属性）。
     * 时序安全前提（已核对）：jumpTo/jumpToTask 入口同步置 Preparing
     * （与镜像旧写点之间为纯同步主线程代码，无挂起/分发 interleaved）。
     * 2026-08-22 #159 收口：ChatMessageList 手工镜像已删除，autoLoad 启动
     * 门控改读 [jumpLockActive]（本类派生——见其文档）。
     */
    val isJumpInProgress: Boolean
        get() = _phase.value is JumpPhase.Preparing ||
            _phase.value is JumpPhase.Measuring ||
            _phase.value is JumpPhase.Settling

    /** UI 派生：蒙版显示（= 跳转进行中）。 */
    val showMask: Boolean get() = isJumpInProgress

    /** UI 派生：目标可显示（Displayed/Failed——settled 取代）。 */
    val gateOpen: Boolean
        get() = _phase.value is JumpPhase.Displayed || _phase.value is JumpPhase.Failed

    /** 跳转（快速导航——user 消息目标）。 */
    fun jumpTo(msgId: String, lazyIndex: Int) {
        cancelPreviousJump() // A-F1/D-1：旧代协程（含稳定窗口）立即失效
        targetKeyPrefix = "u"
        currentTargetMsgId = msgId
        _phase.value = JumpPhase.Preparing(msgId)
        activeJob = scope.launch {
            try {
            // Preparing → ParsedReady：user 目标不再预解析/等待（2026-08-21
            // D-10 附带修复）——PartContent isUser 分支纯 Text 渲染（MarkdownState/
            // preParsedState 均被忽略），原 preParse + 2.5s await 对视觉零贡献
            //（纯延迟）。assistant 目标（jumpToTask）本就无预解析（渲染供给
            // 协调器覆盖窗口内容）。移除后 user 跳转直通 Measuring。
            if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: Preparing 开始 msg=${msgId.take(12)}")
            _phase.value = jumpTransition(_phase.value, JumpEvent.ParsedReady)
            if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: 进入测量 msg=${msgId.take(12)} idx=$lazyIndex")
            // Measuring：一次定位到最终位置（估算高度——目标不再移动，避免回收振荡）
            measureAndSettle(msgId, lazyIndex)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 被新跳转取消：静默退出（新代已接管 phase/滚动，不写穿）
                throw e
            }
        }
    }

    /** 定位发起卡片（assistant 目标——同状态机，参数化目标）。 */
    fun jumpToTask(lazyIndex: Int, targetMsgId: String) {
        cancelPreviousJump() // A-F1/D-1：同 jumpTo
        targetKeyPrefix = "t"
        currentTargetMsgId = targetMsgId
        _phase.value = JumpPhase.Preparing(targetMsgId)
        activeJob = scope.launch {
            // 无预解析（assistant 目标）——直接进入测量
            _phase.value = jumpTransition(_phase.value, JumpEvent.ParsedReady)
            measureAndSettle(targetMsgId, lazyIndex)
        }
    }


    /**
     * 测量 + 收敛（目标在最终位置——一次定位）。
     * 估算高度定位 → 透明测量（Ready + 列表同步）→ 收敛小修正 → Displayed + 稳定窗口。
     */
    private suspend fun measureAndSettle(msgId: String, lazyIndex: Int) {
        // Measuring 定位：**底部对齐**（offset=0——目标底边贴视口底——一定在
        // 视口内，不会因估算高度偏差滚过头/丢失；蒙版遮住后续移动）。
        // A-F4（2026-08-21）：跳转路径改官方挂起 scrollToItem——显式导航
        // 接管视口，取消进行中的用户 fling 是预期语义（反射 NoCancel 仅保留
        // 给 SSE 高度补偿——ChatMessageList 两处调用点）。scrollToItem 内部
        // 同为 requestPositionAndForgetLastKnownKey（锚 key 语义不变），但经
        // scroll{} 互斥锁有序执行，无反射成员依赖。
        if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: 底部定位 idx=$lazyIndex")
        listState.scrollToItem(lazyIndex, 0)
        kotlinx.coroutines.delay(32)  // 等 2 帧（约 16ms/帧）——非组合环境用 delay 替代 withFrameNanos
        if (BuildConfig.DEBUG) {
            val vis = listState.layoutInfo.visibleItemsInfo.map { "${it.index}:${it.key}" }.take(12)
            AppLogger.d("ChatPaging", "jump: 底部定位后可见=[$vis]")
        }

        // ===== 渐进定位（2026-08-13 根治——窗口模式实测暴露结构性问题） =====
        // 根因：一次大滚动（vh - H - pt ≈ 1477px）把视口顶部换成大量未组合内容
        // → Markdown 渐进测量（214→331 级跳变）→ 目标被推 → 滚出视口 → item
        // 回收 → 重建重解析 → 振荡（headless 布局时序恰好未触发，窗口模式暴露）。
        // 根治：目标从视口底部**小步逼近**顶部（每步 ≤ vh/2——新进入视口的内容
        // 少、渐进测量量小、稳定快）；每步后等待**区域稳定**（全部可见 item 的
        // key:size 签名连续 4 轮不变——目标及其上下邻居都在渐进测量中也不误判）；
        // 目标全程在视口内（不回收、不重建）——机制上消除振荡。
        var lastRegionSig: String? = null
        var stableCount = 0
        var nullStreak = 0
        var lastRelocateAt = 0L
        var settled = false
        var finalHeight = -1
        withTimeoutOrNull(5000) {
            while (true) {
                kotlinx.coroutines.delay(100)
                // A-F4：重定位判定与执行分离——官方 scrollToItem 内部经 scroll{}
                // 互斥锁，不能在下方已持锁的 scroll{} 块内调用（会取消本轮
                // 收敛循环自身；旧实现因此只能反射绕锁）。块内只置标记，块外
                // 挂起上下文执行官方 API。
                var needRelocate = false
                listState.scroll {
                    val info = listState.layoutInfo
                    val item = findJumpTargetItem(info.visibleItemsInfo, targetKey(msgId))
                    if (item == null) {
                        // 防御：极端布局下目标被推出——节流重定位（底部对齐——
                        // 目标回视口内重新渐进）
                        nullStreak++
                        if (nullStreak >= 2) needRelocate = true
                        lastRegionSig = null
                        stableCount = 0
                        return@scroll
                    }
                    nullStreak = 0
                    // 区域签名：全部可见 item 的 key:size——任何 item（含邻居）的
                    // 渐进测量都打破稳定，避免"目标稳定但邻居在变"的误判
                    val regionSig = info.visibleItemsInfo
                        .sortedBy { it.index }
                        .joinToString("|") { "${it.key}:${it.size}" }
                    if (regionSig != lastRegionSig) {
                        lastRegionSig = regionSig
                        stableCount = 0
                        return@scroll
                    }
                    stableCount++
                    if (stableCount < 4) return@scroll
                    // 区域稳定（连续 4 轮签名不变）——计算目标顶边偏差
                    val vhNow = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
                    val ptNow = -info.viewportStartOffset.toFloat()
                    val gapToTop = computeGap(item.offset, item.size, vhNow, ptNow)
                    if (BuildConfig.DEBUG) {
                        AppLogger.d("ChatPaging", "jump: 渐进 gap=$gapToTop size=${item.size} region=[$regionSig]")
                    }
                    finalHeight = item.size
                    if (kotlin.math.abs(gapToTop) <= 2f) {
                        settled = true
                        return@scroll
                    }
                    // 小步滚动：偏差 ≤ vh/2 时一步到位；否则步进 vh/2（新内容可控）
                    val step = when {
                        gapToTop < 0 -> maxOf(-(vhNow / 2).toInt(), gapToTop.toInt())
                        else -> minOf((vhNow / 2).toInt(), gapToTop.toInt())
                    }
                    if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: 渐进步进 step=$step")
                    val actualScrolled = scrollBy(step.toFloat())
                    lastRegionSig = null
                    stableCount = 0
                    // 2026-08-21 夹持修复（幽灵 gap 真根因）：前向跳到列表端附近时
                    // 目标下方内容不足一屏——gap 物理上无法归零，scrollBy 被内容
                    // 边界夹持（实际位移 < 请求位移）。旧实现继续空转步进直至 5s
                    // 超时 TimedOut（真机日志：step=-343 ×7 无效步进）——现检测到
                    // 夹持即接受当前物理最接近位置收场（Displayed，省 ~3.5s 蒙版）。
                    // 稳定窗口的 gap 修正对夹持位置是天然 no-op（滚不动即不动）。
                    if (kotlin.math.abs(actualScrolled - step) > 1f) {
                        if (BuildConfig.DEBUG) {
                            AppLogger.d("ChatPaging", "jump: 滚动被内容边界夹持（请求${step}/实际${actualScrolled.toInt()}）——接受当前位置")
                        }
                        settled = true
                        return@scroll
                    }
                }
                if (needRelocate) {
                    // 时钟基（D-4）：elapsedRealtime 单调——currentTimeMillis 可被
                    // NTP/手动调时倒退，300ms 节流假失效/永失效。
                    val nowReloc = android.os.SystemClock.elapsedRealtime()
                    if (nowReloc - lastRelocateAt > 300) {
                        val freshIndex = resolveLazyIndex(msgId) ?: lazyIndex
                        if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: 重定位 idx=$freshIndex（null=$nullStreak）")
                        listState.scrollToItem(freshIndex, 0)
                        lastRelocateAt = nowReloc
                        nullStreak = 0
                    }
                }
                if (settled) break
            }
        }
        if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: 布局稳定 ${if (finalHeight >= 0) "finalHeight=$finalHeight" else "超时"}")
        if (!settled) {
            _phase.value = jumpTransition(_phase.value, JumpEvent.TimedOut("measuring"))
            return
        }
        // 渐进定位完成 = 测量稳定 + 收敛完成（目标已贴视口顶）——状态机直通
        _phase.value = jumpTransition(_phase.value, JumpEvent.MeasureReady)
        _phase.value = jumpTransition(_phase.value, JumpEvent.Settled)
        if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: Displayed（渐进定位完成）")

        // 稳定窗口（C-R2/D-4 修正 2026-08-20）：显示后监控 gap 变化并静默修正。
        // 旧版 1.5s（10×150ms）且 scroll{} 走 MutatorMutex——用户跳转后立即滚动
        // 会被修正循环『杀死』（滚动卡主因）；且 1.5s 协程 delay vs 2s 墙钟门控
        // 在巨帧下时钟错配。改为：用户开始触摸即退出窗口（isScrollInProgress
        // 由手势置位）+ 总时长缩至 900ms + gap 显著才修（>8f，小漂移不抢滚动）。
        val settleStart = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - settleStart < 900) {
            kotlinx.coroutines.delay(150)
            if (listState.isScrollInProgress) {
                // 用户已接管滚动——立即放弃修正（不再与手势对拉）
                if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: 稳定窗口提前退出（用户滚动）")
                break
            }
            listState.scroll {
                val info4 = listState.layoutInfo
                val it4 = findJumpTargetItem(info4.visibleItemsInfo, targetKey(msgId))
                if (it4 != null) {
                    val vh4 = (info4.viewportEndOffset - info4.viewportStartOffset).toFloat()
                    val pt4 = -info4.viewportStartOffset.toFloat()
                    val gap4 = computeGap(it4.offset, it4.size, vh4, pt4)
                    if (kotlin.math.abs(gap4) > 8f) scrollBy(gap4)
                }
            }
        }
        if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: 稳定窗口结束")
    }
}
