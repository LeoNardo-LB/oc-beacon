package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

// ==== #245 巨帧分块守卫（输入合并巨帧的定向防御） ====
//
// 现场取证（2026-08-27 真机，PtrDiag 探针三轮，journal §八轮/#245）：冷启动
// 进场后，平台把整段 2.5s 拖动合并成 2-3 个巨型 move（单帧 ~850px，正常手势
// 每帧 ≤40px）送达，列表 scrollable 认领手势（isScrollInProgress=true）却对
// 巨帧零消耗——列表最外层探针 consumed=0、idx/off 钉死，拖动全灭；轻点/
// 键盘/程序化滚动不受影响。守卫内打点确认 dispatchRawDelta 是否被调用及
// 返回值，是定界 app/框架的下一跳（效果存疑，已如实注记；健康帧零触碰）。
//
// 机制勘误（v2 形态）：NestedScrollConnection.onPreScroll 只承接**来自更深层
// 滚动器**的流——列表自身的拖动不经过祖先连接（v1 形态真机 3/3 空转证实）。
// 正确拦截位 = Initial 隧道趟（parent→child，先于子滚动器 Main 趟的消费）。

/** 巨帧判定阈值（px/帧）：正常拖拽帧远低于此；合并帧 ≥400。 */
internal const val MEGA_DELTA_THRESHOLD_PX = 300f

/** 分块直派切片（px）。 */
private const val CHUNK_PX = 100f

/**
 * #245 巨帧分块守卫（Initial 隧道趟）——挂在会话 LazyColumn 修饰符链上。
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
