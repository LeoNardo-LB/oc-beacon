package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.ui.screens.chat.util.snapToBottom
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.State
import kotlinx.coroutines.withTimeoutOrNull

/** ChatScrollController 专属日志 TAG。 */
private const val TAG = "ChatScrollController"

/**
 * 滚动状态簇 —— 从 ChatScreen 抽取。
 *
 * 封装 autoScroll / forceScroll / isAtBottom 三组滚动状态及其 LaunchedEffect，
 * 由 [rememberChatScrollController] 创建并通过本类暴露给 ChatScreen。
 *
 * **行为铁律**：双 key LaunchedEffect（`listState.isScrollInProgress, isAtBottom`）
 * 原样搬移自 ChatScreen，不可改动逻辑或移除 `isAtBottom` 作为 key
 * （参见 docs/research/sse-scroll-stability-iron-laws.md）。
 */
internal class ChatScrollController(
    val listState: LazyListState,
    val isAtBottomState: State<Boolean>,
    private val autoScrollEnabledState: MutableState<Boolean>,
    private val forceScrollTickState: MutableIntState,
) {
    /** 列表是否锚定在底部（firstVisibleItemIndex==0 且 scrollOffset<100）。
     * 2026-08-20 B-F5：改为 State 直传——调用方（ChatScreen 819/847）原先
     * 在组合作用域读 Boolean getter，每次底部阈值跨越触发整个 ChatScreen
     * 重组（PerfMon anim 相位 13-33ms 周期爆发的主源）。消费方应把 .value
     * 读取下沉到 snapshotFlow / 最小组合作用域。 */
    val isAtBottom: Boolean get() = isAtBottomState.value

    /** autoScroll 的快照可观察形态（#222：尾部横幅 reveal 门控读取点——
     * ChatMessageList 的 reveal effect 需订阅「在底意图」，而非 isAtBottom
     * （横幅插入本身会把锚 index 抬高使 isAtBottom 翻 false，用它门控会
     * 自我闭锁）。读取底层 MutableState，快照可观察。 */
    val autoScrollState: State<Boolean> get() = autoScrollEnabledState

    /**
     * 自动滚动开关。后端为 [rememberSaveable] 的 [MutableState]，跨配置变更存活。
     * 修改会真实作用于底层状态（不会因 controller 实例每次重组新建而丢失）。
     */
    var autoScrollEnabled: Boolean
        get() = autoScrollEnabledState.value
        set(value) { autoScrollEnabledState.value = value }

    /** 强制滚动触发计数（只读快照）。 */
    val forceScrollTick: Int get() = forceScrollTickState.intValue

    /** 触发一次强制滚动到底部（自增 forceScrollTick 并强制启用自动跟随）。 */
    fun forceScrollToBottom() {
        // 发送/跳底场景强制开启自动跟随 —— 用户发消息后必然想看新消息，
        // 即使此前滚动到中间（autoScrollEnabled=false）也应跳到底部。
        autoScrollEnabledState.value = true
        forceScrollTickState.intValue++
    }
}

/**
 * 创建并 remember [ChatScrollController]，托管 ChatScreen 的全部滚动副作用。
 *
 * @param listState 由 ViewModel 提升的列表状态（reverseLayout=true）
 * @param messageCount 当前消息数量，驱动自动滚动到顶部
 * @param pendingCount 待处理问题/权限数，驱动 pending 项滚动到底
 * @param hasMessages 返回消息列表是否非空；**必须是捕获了可重新读取的 State 的 lambda**
 *        （如 `{ messageState.messages.isNotEmpty() }`，其中 `messageState` 为
 *        `by collectAsStateWithLifecycle()` 委托属性），以便内部
 *        `snapshotFlow(hasMessages)` 能正确订阅 State 变化。传入捕获快照值的
 *        lambda 会导致 snapshotFlow 永不重算 → `.first { it }` 死锁。
 */
@Composable
internal fun rememberChatScrollController(
    listState: LazyListState,
    messageCount: Int,
    pendingCount: Int,
    hasMessages: () -> Boolean,
): ChatScrollController {
    val autoScrollEnabled = rememberSaveable { mutableStateOf(true) }
    val forceScrollTick = remember { mutableIntStateOf(0) }

    val isAtBottomState = remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset < 100
        }
    }
    // 重要（铁律等价改写 2026-08-20 B-F5）：原双 key LaunchedEffect 的语义 =
    // isScrollInProgress / isAtBottom 任一变化都要重估（用户通过非拖拽方式
    // 回到底部时重置 autoScroll——fling 惯性、SSE 推送、补偿滚动）。
    // snapshotFlow 双值流保持完全相同的反应性（任一变化即发射、顺序执行
    // 同一逻辑体），但把 State 读取从组合作用域移进流——本工厂函数原先在
    // ChatScreen 作用域读 isAtBottomState.value，每次阈值跨越整个 ChatScreen
    // 重组（PerfMon 实测 anim 相位周期爆发主源）。
    LaunchedEffect(listState, isAtBottomState) {
        snapshotFlow { listState.isScrollInProgress to isAtBottomState.value }
            .collect { (scrolling, atBottom) ->
                if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                    AppLogger.d(
                        "ChatScrollController",
                        "[DEBUG-drift] atBot=" + atBottom + " scrolling=" + scrolling +
                            " autoOn=" + autoScrollEnabled.value +
                            " idx=" + listState.firstVisibleItemIndex +
                            " off=" + listState.firstVisibleItemScrollOffset
                    )
                }
                if (scrolling) {
                    autoScrollEnabled.value = false
                } else if (atBottom) {
                    autoScrollEnabled.value = true
                }
            }
    }

    // 新消息到达 → 同帧位置锚定到底部（index 0），而非"跟随最后一条消息的 key"。
    // requestScrollToItem 非挂起：在 effect（apply 后、layout 前）同步注册请求，
    // 下一帧布局直接按位置定位 —— 无"旧 key 锚定偏移一帧 → 再拉回"的闪烁循环。
    LaunchedEffect(messageCount) {
        if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
            AppLogger.w(
                TAG,
                "[DEBUG-drift] MSGEFFECT fire n=$messageCount autoOn=" + autoScrollEnabled.value +
                    " scrollIp=" + listState.isScrollInProgress +
                    " idx=" + listState.firstVisibleItemIndex
            )
        }
        if (messageCount > 0 && autoScrollEnabled.value) {
            // [probe] msgCount effect n=$messageCount autoScroll=${autoScrollEnabled.value} scrollInProgress=${listState.isScrollInProgress}
            // 2026-08-16 根治：死代码根因。原实现 `!listState.isScrollInProgress`
            // 条件失败（新消息恰逢用户 fling 惯性中到达）时静默跳过且不重试。
            // 现改为等待 fling 真实停止（snapshotFlow 订阅真实 State）后再锚定，
            // 带 2s 兜底防死等。
            if (listState.isScrollInProgress) {
                withTimeoutOrNull(2_000) {
                    snapshotFlow { listState.isScrollInProgress }.first { !it }
                }
            }
            // 2026-08-17 根治（fling 跳底）：effect 启动时读到的 autoScroll 是
            // 一次性快照——若用户恰在 effect 启动后开始拖动（快照时 autoScroll
            // 仍 true），等待 fling 结束后无条件 requestScrollToItem(0) 会把
            // 用户正翻看的历史位置强拉回底部。等待结束后**重新校验**：用户
            // 拖动已置 autoScroll=false 则放弃锚定（尊重用户的阅读位置）。
            if (autoScrollEnabled.value) {
                if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                    AppLogger.w(
                        TAG,
                        "[DEBUG-drift] MSGEFFECT anchor n=$messageCount off=" +
                            listState.firstVisibleItemScrollOffset
                    )
                }
                listState.requestScrollToItem(0)
                // 2026-08-30 下跳回归根修：requestScrollToItem 是一次性锚定——
                // 打开时默认展开的卡（shell/事件卡）+ RB/TC/Markdown 异步内容
                // 在打开后持续长高（实测 600ms-数秒不等），把 item0 渐渐顶离
                // 贴底（实测漂移 190-444px，atBot 翻 false、FAB 浮现）。此后
                // 对底部卡的收起/展开不再走贴底透传，而是 mid-list 注入路径
                // （实测单次 toggle 注入 ±444px 视口位移）——可见下坠，且首次
                // toggle 注入链会顺手把列表重锚回贴底（「第一次跳、后面不跳」
                // 的成因）。守卫 = autoScroll 语义的补全：用户未取消跟随
                // （未滚动）期间，任何离底漂移都重新锚定；用户滚动立即让位
                // （铁律：用户阅读位置优先权不变）。跟随模式存活多久，守卫
                // 就跟多久——无窗口。
                snapshotFlow {
                    Triple(
                        listState.isScrollInProgress,
                        autoScrollEnabled.value,
                        listState.firstVisibleItemIndex == 0 &&
                            listState.firstVisibleItemScrollOffset < 100,
                    )
                }.collect { (scrolling, autoOn, atBottom) ->
                    if (!scrolling && autoOn && !atBottom) {
                        if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                            AppLogger.w(
                                TAG,
                                "[DEBUG-drift] GUARD reanchor idx=" + listState.firstVisibleItemIndex +
                                    " off=" + listState.firstVisibleItemScrollOffset
                            )
                        }
                        listState.requestScrollToItem(0)
                    }
                }
            }
        }
    }

    LaunchedEffect(forceScrollTick.intValue) {
        if (forceScrollTick.intValue > 0) {
            dev.leonardo.ocbeacon.logging.AppLogger.d(TAG, "[probe] force tick=$forceScrollTick.intValue autoScroll=${autoScrollEnabled.value}")
            // 2026-08-16 根治：死代码根因。原实现 `snapshotFlow { messageCount }`
            // 捕获的是不可变 Int 函数参数（非 State），block 内零 State 读取 →
            // flow 只发射一次初始值 → `.first{}` 永久挂起，连 5s 兜底（写在
            // 永不重跑的谓词内）都不执行 —— tick 路径完全不滚。
            // 现改为经 [ForceScrollExecutor] 订阅 LazyListState.layoutInfo
            // （derived state，随组合/布局更新重新求值）等待真实增长。
            ForceScrollExecutor(gate = LazyListStateGate(listState)).execute()
        }
    }

    LaunchedEffect(pendingCount) {
        if (pendingCount > 0 && autoScrollEnabled.value) {
            snapshotFlow(hasMessages).first { it }
            // 2026-08-17 根治（fling 跳底）：pending 卡片注入（进入会话时
            // loadPending 延迟到达）触发的 snapToBottom 不能打断用户手势——
            // 用户正在拖动/fling 时跳过本次（下一个 effect 周期或 force 路径
            // 兜底）；等待后再重新校验 autoScroll（拖动已关闭则不动）。
            if (listState.isScrollInProgress) {
                withTimeoutOrNull(2_000) {
                    snapshotFlow { listState.isScrollInProgress }.first { !it }
                }
            }
            if (autoScrollEnabled.value) {
                if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                    AppLogger.w(
                        TAG,
                        "[DEBUG-drift] PENDING animate pendingCount=" + pendingCount +
                            " fromIdx=" + listState.firstVisibleItemIndex +
                            " fromOff=" + listState.firstVisibleItemScrollOffset
                    )
                }
                // 2026-08-30 问题卡方向修复：snapToBottom 的瞬跳把整个对话
                // 一把推上去（用户观感「向上展开」）；animateScrollToItem 平滑
                // 下滑揭示，问题卡随视口自头部下方逐帧展开 = 向下展开。
                listState.animateScrollToItem(0)
            }
        }
    }

    return ChatScrollController(
        listState = listState,
        isAtBottomState = isAtBottomState,
        autoScrollEnabledState = autoScrollEnabled,
        forceScrollTickState = forceScrollTick,
    )
}

/**
 * LazyListState 的最小滚动门面 —— 2026-08-16 根治：死代码根因。
 *
 * 目的：把"等待 + 滚动 + 校验"逻辑从 Composable 抽到 [ForceScrollExecutor]，
 * 使其可以在无 UI/布局环境的 JVM 单测中用 State 驱动的 Fake 验证。
 * 生产实现为 [LazyListStateGate]，属性直读 LazyListState 的快照/derived state。
 */
internal interface ScrollListGate {
    /** 列表总项数（对应 `LazyListState.layoutInfo.totalItemsCount`）。 */
    val totalItemsCount: Int

    /** 是否有滚动（拖动/fling/程序化）正在进行。 */
    val isScrollInProgress: Boolean

    val firstVisibleItemIndex: Int
    val firstVisibleItemScrollOffset: Int

    /** 非挂起位置请求（下一帧布局生效），对应 `LazyListState.requestScrollToItem`。 */
    fun requestScrollToItem(index: Int)
}

/** [ScrollListGate] 的生产实现：直读 [LazyListState] 的快照属性。 */
internal class LazyListStateGate(private val state: LazyListState) : ScrollListGate {
    override val totalItemsCount: Int get() = state.layoutInfo.totalItemsCount
    override val isScrollInProgress: Boolean get() = state.isScrollInProgress
    override val firstVisibleItemIndex: Int get() = state.firstVisibleItemIndex
    override val firstVisibleItemScrollOffset: Int get() = state.firstVisibleItemScrollOffset
    override fun requestScrollToItem(index: Int) = state.requestScrollToItem(index)
}

/**
 * 强制滚底执行器（发送后跟随）—— 2026-08-16 根治：死代码根因。
 *
 * 流程：
 * 1. 等待 [ScrollListGate.totalItemsCount] 真实增长（订阅 derived state，替代
 *    捕获不可变 Int 参数的 snapshotFlow —— 那是 flow 只发射一次、`.first{}`
 *    永久挂起、5s 兜底也永不执行的死代码根因）。最多 [GROWTH_TIMEOUT_MS]。
 * 2. 若用户 fling 惯性中，等待其停止（最多 [FLING_TIMEOUT_MS]）—— 替代
 *    一次性条件检查的静默跳过。
 * 3. `requestScrollToItem(0)` 锚定底部，等一帧布局后校验位置；未到位则再等
 *    [VERIFY_TIMEOUT_MS]（布局补偿收敛），仍不到位则重滚一次。
 *
 * @param gate 滚动门面（生产=LazyListStateGate；测试=State 驱动 Fake）
 * @param onGrowthTimeout 消息增长等待超时的日志回调（测试中兼作断言探针）
 * @param waitOneFrame 等一帧布局的挂起块（生产 `withFrameNanos`；测试注入空实现，
 *        因 JVM 单测无 MonotonicFrameClock，`withFrameNanos` 会永久挂起）
 */
internal class ForceScrollExecutor(
    private val gate: ScrollListGate,
    private val onGrowthTimeout: (String) -> Unit = { AppLogger.d(TAG, it) },
    private val waitOneFrame: suspend () -> Unit = { withFrameNanos { } },
) {
    /** 底部判定：首项 index==0 且偏移 < 100（reverseLayout 下 0 即最底）。 */
    private fun atBottom(): Boolean =
        gate.firstVisibleItemIndex == 0 && gate.firstVisibleItemScrollOffset < AT_BOTTOM_OFFSET_MAX

    suspend fun execute() {
        val startCount = gate.totalItemsCount
        dev.leonardo.ocbeacon.logging.AppLogger.d(TAG, "[probe] executor start count=$startCount scrollInProgress=${gate.isScrollInProgress}")
        val grew = withTimeoutOrNull(GROWTH_TIMEOUT_MS) {
            snapshotFlow { gate.totalItemsCount }.first { it > startCount }
        }
        dev.leonardo.ocbeacon.logging.AppLogger.d(TAG, "[probe] executor grew=${grew ?: -1} (start=$startCount)")

        // 滚前等待用户 fling 结束（替代一次性 if 静默跳过——fling 中到达是高频场景）
        if (gate.isScrollInProgress) {
            withTimeoutOrNull(FLING_TIMEOUT_MS) {
                snapshotFlow { gate.isScrollInProgress }.first { !it }
            }
        }

        gate.requestScrollToItem(0)

        // 滚后校验：超时兜底路径（grew==null，消息未到/发送失败）与补偿后未到位时重滚一次
        waitOneFrame()
        if (!atBottom()) {
            withTimeoutOrNull(VERIFY_TIMEOUT_MS) {
                snapshotFlow { atBottom() }.first { it }
            } ?: gate.requestScrollToItem(0)
        }

        if (grew == null) {
            onGrowthTimeout("[scroll] force tick timed out waiting for message growth, scrolled anyway")
        }
    }

    private companion object {
        const val GROWTH_TIMEOUT_MS = 5_000L
        const val FLING_TIMEOUT_MS = 2_000L
        const val VERIFY_TIMEOUT_MS = 1_000L
        const val AT_BOTTOM_OFFSET_MAX = 100
    }
}
