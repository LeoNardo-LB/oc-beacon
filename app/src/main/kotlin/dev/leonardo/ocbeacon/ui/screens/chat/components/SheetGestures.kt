package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/**
 * #379（2026-09-10 用户裁决）：抽屉手势纪律共享件——
 * · [SmallSheetDragHandle]：统一小样式手柄（行高很小——28×3dp 圆角条，
 *   窄于 M3 默认 32×4dp）。
 * · [sheetContentGestureIsolation]：内容区手势隔离——抽屉内任何滑动（拖拽或
 *   fling）不得致收起；仅手柄拖动/点外/返回手势收起。
 *
 * 机制（Compose nestedScroll 分发序：child → 本连接 → sheet 连接）：
 * - onPostScroll：内容滚到顶后的**向下剩余量**（收起方向）就地消费——sheet
 *   的 anchoredDrag 收不到内容腿的拖拽位移 → 内容拖拽不塌；向上（负 y）不拦，
 *   正常滚动/加载零影响。
 * - onPostFling：内容 fling 结束后的**向下剩余速度**就地消费——sheet 收不到
 *   惯性速度 → 内容 fling 不致收起；列表自身惯性在 child 腿已消费不受影响。
 * - 手柄区在手柄槽（本连接之外）——手柄拖拽直达 sheet，收起能力保留。
 */

@Composable
fun SmallSheetDragHandle(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(width = 28.dp, height = 3.dp)
                .graphicsLayer { alpha = 0.55f }
                .background(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(1.5.dp),
                ),
        )
    }
}

/** #379：内容区手势隔离（挂 sheet 内容根；见文件头机制说明）。 */
fun Modifier.sheetContentGestureIsolation(): Modifier = nestedScroll(SheetGestureIsolation)

/**
 * #405（2026-09-12）：sheet 内容根的非滚动区（标题带 / 手柄下方空白带）不发起 sheet 拖拽。
 *
 * 为什么 nestedScroll 隔离不够：nestedScroll 只接收**可滚动子节点**派发的位移
 * （LazyColumn）——标题带/空白带没有滚动子节点，向下拖拽直接落到 M3 sheet 自身的
 * anchoredDraggable（androidx 源码：dragHandle 仅 visual marker，整表可拖），
 * 于是从这些带下滑会收起抽屉，违背 #379「仅手柄 / 点外 / 返回收起」的既定意图。
 *
 * 机制：挂在内容根（与 [sheetContentGestureIsolation] 同节点）的 pointerInput 于
 * **Main 趟**（child→parent）处理**未被任何可滚动子节点消费**的向下指针位移——
 * 列表可滚动时其 scrollable 先消费，本连接跳过；标题/空白带无子消费，本连接就地
 * 消费 → 父层 sheet 拖拽检测器因已消费而取消。仅拦向下（收起方向）。
 */
fun Modifier.sheetNonScrollableDragBlock(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var lastY = down.position.y
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val dy = change.position.y - lastY
            lastY = change.position.y
            if (!change.isConsumed && dy > 0f) change.consume()
        }
    }
}

private val SheetGestureIsolation = object : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = if (available.y > 0f) available else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (available.y > 0f) available else Velocity.Zero
}
