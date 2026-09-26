package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.lazy.LazyListState
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.logging.AppLogger

/**
 * #437 引擎③：视口写入单点派发网关。
 *
 * 跟随族（GUARD/MSGEFFECT/BANNER）与显式意图族（ForceScroll/PENDING/SNAP）
 * 全部经此写入并打点（SGR-GATE）——任何未经网关的程序化视口写入在日志
 * 时间线上立即可辨。
 *
 * ## [R5] 网关外写入显式登记（二十五世轮审查裁决：从 KDoc 自我豁免改为显式清单）
 *
 * | 写入族 | 通道 | 豁免依据 |
 * |---|---|---|
 * | 引擎配对/帽释放 | LazyListReflection.requestScrollToItemNoCancel（单出口事务） | 引擎本体（ScrollCompensation 单出口） |
 * | 卡片展开 episode | dispatchRawDelta / requestScrollToItemNoCancel | 引擎配对机器（CardExpandReveal） |
 * | 跳转族 | JumpNavigationController / QuickNavigateSheet scrollToItem | 用户显式意图（位置神圣铁律） |
 * | snapToBottom 强推 | ChatScrollUtils scrollBy ×3 | 显式意图的钉底兜底（首跳已打点） |
 * | ScrollIsland 手势分块 | dispatchRawDelta | 手势通道（巨帧重整形） |
 *
 * 新增网关外写入必须在本表登记并给出豁免依据（review 检查项）；强制化
 * （封装 LazyListState / lint 禁裸调用）为后续批次。
 *
 * 语义保持：不改任何门控（各调用点原有判定不变），只收拢写入与观测。
 */
internal object ViewportDispatchGateway {

    private fun log(kind: String, listState: LazyListState, family: String, gate: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.d(
                "SGR-GATE",
                kind + " t=" + android.os.SystemClock.elapsedRealtime() +
                    " family=" + family + " gate=" + gate +
                    " fii=" + listState.firstVisibleItemIndex +
                    " fiso=" + listState.firstVisibleItemScrollOffset,
            )
        }
    }

    /** 贴底跟随族：锚定列表原点（reverseLayout 索引 0=最底）；离底静默由调用点门控。 */
    fun bottomFollow(listState: LazyListState, family: String, gate: String) {
        log("bottom-follow", listState, family, gate)
        listState.requestScrollToItem(0)
    }

    /** 显式意图族（发送/压缩跟随、FAB 吸附）——离底放行是语义本身。 */
    fun explicitPin(listState: LazyListState, family: String, gate: String) {
        log("explicit-pin", listState, family, gate)
        listState.requestScrollToItem(0)
    }

    /** 显式意图族·无状态句柄变体（ScrollListGate 包装层——ForceScroll 校验重滚）。 */
    fun explicitPinAction(family: String, gate: String, action: () -> Unit) {
        if (BuildConfig.DEBUG) {
            AppLogger.d("SGR-GATE", "explicit-pin t=" + android.os.SystemClock.elapsedRealtime() + " family=" + family + " gate=" + gate)
        }
        action()
    }

    /** 显式意图族·动画通道（PENDING 问题卡平滑揭示）。 */
    suspend fun explicitAnimatePin(listState: LazyListState, family: String) {
        log("explicit-animate", listState, family, "")
        listState.animateScrollToItem(0)
    }
}
