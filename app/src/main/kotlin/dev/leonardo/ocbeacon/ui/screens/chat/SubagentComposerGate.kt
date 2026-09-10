package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.SubagentCatalogEntry

/**
 * #310① 子会话 composer 门控（纯函数，单测 SubagentComposerGateTest）。
 *
 * - 主会话（parentSessionId == null）恒开——既有路径零语义变化；
 * - 仅子智能体能力位在场时子会话可续聊（mode=continuable 开；one-shot web 禁 composer 同款）；
 * - 无该能力的类型子会话维持既有只读镜像（恒关）；
 * - 加载中/失败/目录无本行（mode == null）保守关——防 one-shot 误发。
 */
internal object SubagentComposerGate {

    fun composerVisible(parentSessionId: String?, subagentsSupported: Boolean, mode: String?): Boolean =
        parentSessionId == null ||
            (subagentsSupported && mode == SubagentCatalogEntry.MODE_CONTINUABLE)

    /** one-shot 只读提示行（明确不可续聊；加载中/失败不显示——静默保守隐藏）。 */
    fun readOnlyHintVisible(parentSessionId: String?, subagentsSupported: Boolean, mode: String?): Boolean =
        parentSessionId != null &&
            subagentsSupported &&
            mode == SubagentCatalogEntry.MODE_ONE_SHOT
}
