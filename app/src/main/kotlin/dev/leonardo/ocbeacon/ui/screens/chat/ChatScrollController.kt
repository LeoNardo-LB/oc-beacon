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
import dev.leonardo.ocbeacon.ui.screens.chat.util.snapToBottom
import kotlinx.coroutines.flow.first

/**
 * 滚动状态集群 —— 从 ChatScreen 抽取。
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
    private val isAtBottomProvider: () -> Boolean,
    private val autoScrollEnabledState: MutableState<Boolean>,
    private val forceScrollTickState: MutableIntState,
) {
    /** 列表是否锚定在底部（firstVisibleItemIndex==0 且 scrollOffset<100）。 */
    val isAtBottom: Boolean get() = isAtBottomProvider()

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
    val isAtBottom = isAtBottomState.value

    // 重要：同时以 isScrollInProgress 和 isAtBottom 作为 key。
    // 以 isAtBottom 作为 key 可以让本效果在用户通过非拖拽方式（fling 惯性、
    // SSE 内容推送、补偿滚动）回到底部时重新求值 —— 仅用 isScrollInProgress
    // 会错过这些转换，导致 autoScrollEnabled 停留在陈旧状态。这种双 key 形式
    // 是经过 beta.360 验证的行为；不要把 isAtBottom 从 key 中移除（参见
    // docs/research/sse-scroll-stability-iron-laws.md）。
    LaunchedEffect(listState.isScrollInProgress, isAtBottom) {
        if (listState.isScrollInProgress) {
            autoScrollEnabled.value = false
        } else if (isAtBottom) {
            autoScrollEnabled.value = true
        }
    }

    // 新消息到达 → 同帧位置锚定到底部（index 0），而非"跟随最后一条消息的 key"。
    // requestScrollToItem 非挂起：在 effect（apply 后、layout 前）同步注册请求，
    // 下一帧布局直接按位置定位 —— 无"旧 key 锚定偏移一帧 → 再拉回"的闪烁循环。
    LaunchedEffect(messageCount) {
        if (messageCount > 0 && autoScrollEnabled.value && !listState.isScrollInProgress) {
            listState.requestScrollToItem(0)
        }
    }

    LaunchedEffect(forceScrollTick.intValue) {
        if (forceScrollTick.intValue > 0) {
            // 2026-08-16 修复（发送后不滚底）：悲观消息模式下点击发送时消息
            // **尚未进列表**（POST 往返 + SSE 回显后才有）——原实现立即
            // requestScrollToItem(0) 滚到当前底部（落空），随后消息插入时
            // autoScrollEnabled 可能已被关 → 不跟随。
            // 修复：等待消息数实际增加后再锚定底部（最多等 5s 兜底——
            // 发送失败/无回显时仍执行一次避免死等）。
            val startCount = messageCount
            val startTime = System.currentTimeMillis()
            snapshotFlow { messageCount }
                .first { it > startCount || System.currentTimeMillis() - startTime > 5_000 }
            listState.requestScrollToItem(0)
        }
    }

    LaunchedEffect(pendingCount) {
        if (pendingCount > 0 && autoScrollEnabled.value) {
            snapshotFlow(hasMessages).first { it }
            listState.snapToBottom()
        }
    }

    return ChatScrollController(
        listState = listState,
        isAtBottomProvider = { isAtBottomState.value },
        autoScrollEnabledState = autoScrollEnabled,
        forceScrollTickState = forceScrollTick,
    )
}
