package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * #244 卡内滚动区到边吞噬（嵌套滚动岛）。
 *
 * 症状（#234 F2 复验实证）：事件卡 300dp 展开区滚到底后继续 fling，剩余速度
 * 穿透外层 LazyColumn 把整卡滚走（多轮取证因此反复丢定位）；思考块 / 工具卡
 * 输出区同理。这是标准 nested scroll 行为（子到边 → 父接管），但「卡内小滚动窗
 * + 会话大列表」的组合体感极差——轻甩一下就把展开上下文整个甩丢。
 *
 * 方案：在内嵌 scrollable 的**外侧**挂 [NestedScrollConnection]（同一修饰符链上
 * nestedScroll 位于 verticalScroll 之前，或包裹内层 LazyColumn 时位于其内容之外
 * = 恰好处于子滚动器与会话列表之间）：
 * - onPreScroll / onPreFling 走 children→parent 序、在子自身消耗之前触发；
 *   当子已停在边界且来势同向时把该帧的 y 全量吃掉，外层列表收不到任何剩余
 *   位移/速度——两个边界方向都封死，中段完全放行；
 * - 无可滚内容时整块透明：短内容区域不劫持手势（用户在任何位置滑动都能照常
 *   滚动会话列表）。
 *
 * 用法（verticalScroll 家族——nestedScroll 必须在 verticalScroll **之外**，
 * 与 heightIn 同侧；heightIn/verticalScroll 相对顺序铁律不变见 EventCard 注释）：
 * ```
 * .heightIn(max = 300.dp)
 * .nestedScroll(rememberScrollIsland(scrollState))
 * .clipToBounds()
 * .verticalScroll(scrollState)
 * ```
 * LazyColumn/LazyRow 家族（WebSearch/Glob 结果列表）直接包在其修饰符链上即可。
 */

/**
 * 边界吞噬判定（纯函数，JVM 单测覆盖）：返回应当消耗的 y 分量（0f = 放行）。
 *
 * 符号约定与 Compose 一致：手指向下滑 dy>0 → 向 backward 方向（顶边，元数据
 * 更早的内容）；手指向上滑 dy<0 → 向 forward 方向（底边）。fling 速度同号。
 */
internal fun scrollIslandConsumeY(
    availableY: Float,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    scrollable: Boolean,
): Float = when {
    // 无可滚内容（不足一屏/空）：整体透明，任何方向都放行
    !scrollable -> 0f
    // 在顶边仍在向下压
    availableY > 0f && !canScrollBackward -> availableY
    // 已在底边仍在向上推
    availableY < 0f && !canScrollForward -> availableY
    else -> 0f
}

/** [ScrollState] 家族适配：value==0 即顶边；maxValue<=0 双向皆不可滚=透明。 */
private class ScrollIslandConnection(
    private val state: ScrollState,
) : NestedScrollConnection {
    private fun consume(y: Float): Float {
        val scrollable = state.maxValue > 0
        return scrollIslandConsumeY(
            availableY = y,
            canScrollBackward = scrollable && state.value > 0,
            canScrollForward = scrollable && state.value < state.maxValue,
            scrollable = scrollable,
        )
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        Offset(x = 0f, y = consume(available.y))

    override suspend fun onPreFling(available: Velocity): Velocity =
        Velocity(x = 0f, y = consume(available.y))
}

/** [LazyListState] 家族适配：canScrollBackward/Forward 即边界谓词；空表透明。 */
private class ListIslandConnection(
    private val state: LazyListState,
) : NestedScrollConnection {
    private fun consume(y: Float): Float =
        scrollIslandConsumeY(
            availableY = y,
            canScrollBackward = state.canScrollBackward,
            canScrollForward = state.canScrollForward,
            scrollable = state.canScrollBackward || state.canScrollForward,
        )

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        Offset(x = 0f, y = consume(available.y))

    override suspend fun onPreFling(available: Velocity): Velocity =
        Velocity(x = 0f, y = consume(available.y))
}

/** 记住一个绑定 [state] 的边界岛连接器；配合 nestedScroll 用（见文件 KDoc）。 */
@Composable
fun rememberScrollIsland(state: ScrollState): NestedScrollConnection =
    remember(state) { ScrollIslandConnection(state) }

/** 记住一个绑定内层 [LazyListState] 的边界岛连接器（卡内小列表防穿透）。 */
@Composable
fun rememberListIsland(state: LazyListState): NestedScrollConnection =
    remember(state) { ListIslandConnection(state) }

// ==== #245 巨帧分块（输入合并巨帧的定向防御） ====
//
// 现场取证（2026-08-27 真机，PtrDiag 探针三轮）：冷启动进场后，平台把整段
// 2.5s 拖动合并成 2-3 个巨型 move（单帧 ~850px，正常手势每帧 ≤40px）送达，
// 列表 scrollable 认领手势（isScrollInProgress=true）却对巨帧零消耗——
// 列表最外层探针 consumed=0、idx/off 钉死，拖动全灭；轻点/键盘/程序化滚动
// 不受影响；一旦某次手势恢复正常速率流，后续一切正常。
//
// 机制勘误（v2）：NestedScrollConnection.onPreScroll 只承接**来自更深层
// 滚动器**的流——列表自身的拖动不经过祖先连接，v1 连接器形态永远空转
// （真机复测 3/3 仍冻结证实）。正确拦截位 = Initial 隧道趟（parent→child，
// 先于子滚动器 Main 趟的消费）。
//
// 防御（v2）：列表链上 pointerInput 以 Initial 趟监听，单帧 |Δy| 超阈值的
// 巨帧改为 ≤100px 切片直派 dispatchRawDelta（真实滚动）并消耗事件——
// 正常帧不碰，列表自身管线照旧；只有病态巨帧走分块路径。

/** 巨帧判定阈值（px/帧）：正常拖拽帧远低于此；合并帧 ≥400。 */
internal const val MEGA_DELTA_THRESHOLD_PX = 300f

/** 分块直派切片（px）。 */
private const val CHUNK_PX = 100f

/**
 * #245 巨帧分块守卫（v2，Initial 隧道趟）——挂在会话 LazyColumn 修饰符链上。
 * 只改写单帧超阈值的病态增量；正常手势逐帧放行，行为零变化。
 */
fun Modifier.megaDeltaScrollGuard(state: LazyListState): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var lastY = first.position.y
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val change = ev.changes.firstOrNull() ?: break
                if (!change.pressed) break
                val y = change.position.y
                val dy = y - lastY
                lastY = y
                if (kotlin.math.abs(dy) > MEGA_DELTA_THRESHOLD_PX) {
                    var remaining = dy
                    while (kotlin.math.abs(remaining) > 0.5f) {
                        val slice = remaining.coerceIn(-CHUNK_PX, CHUNK_PX)
                        state.dispatchRawDelta(slice)
                        remaining -= slice
                    }
                    change.consume()
                }
            }
        }
    }
