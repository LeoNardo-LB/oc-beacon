package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.lazy.LazyListState
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.logging.AppLogger

/**
 * #437 引擎③：视口写入单点派发网关（贴底跟随族收编）。
 *
 * 收编前（侦察 2026-09-26 全景）：GUARD / MSGEFFECT / BANNER 三族各自调用
 * requestScrollToItem(0)，门控原语（autoScroll/租约/流式静默/去抖）散落三处。
 * 收编后：写入经本网关单点，family 打点（SGR-GATE）——任何未经网关的程序化
 * 视口写入在日志时间线上立即可辨（VPT 变化无对应 GATE 行=旁路泄漏）。
 *
 * 语义保持：本网关不改任何门控（各调用点原有判定不变），只收拢写入与观测；
 * 引擎后续协调（如流式相位的写入互斥）在此扩展。显式意图族（ForceScroll/
 * PENDING/snapToBottom/跳转）与配对/揭示族（pre-draw 反射通道、episode）为
 * 引擎内部/用户显式通道，不在跟随族收编面（spec §③ 裁决：无特例全收编——
 * 显式族逐点迁移随后续批次，行为已符合「离底零派发仅限跟随族」语义）。
 */
internal object ViewportDispatchGateway {

    /** 贴底跟随族单点写入：锚定列表原点（reverseLayout 索引 0=最底）。 */
    fun bottomFollow(listState: LazyListState, family: String, gate: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.d(
                "SGR-GATE",
                "bottom-follow t=" + android.os.SystemClock.elapsedRealtime() +
                    " family=" + family + " gate=" + gate +
                    " fii=" + listState.firstVisibleItemIndex +
                    " fiso=" + listState.firstVisibleItemScrollOffset,
            )
        }
        listState.requestScrollToItem(0)
    }
}
