package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.ServerType
import dev.leonardo.ocbeacon.domain.model.SubagentCatalogEntry

/**
 * #310① 子会话 composer 门控（纯函数，单测 SubagentComposerGateTest）。
 *
 * - 主会话（parentSessionId == null）恒开——既有路径零语义变化；
 * - DSH 子会话仅 mode=continuable 开（one-shot 官方 web 禁 composer 同款判据）；
 * - OpenCode 子会话维持既有只读镜像（恒关）；
 * - 加载中/失败/目录无本行（mode == null）保守关——防 one-shot 误发。
 */
internal object SubagentComposerGate {

    fun composerVisible(parentSessionId: String?, serverType: ServerType, mode: String?): Boolean =
        parentSessionId == null ||
            (serverType == ServerType.Dsh && mode == SubagentCatalogEntry.MODE_CONTINUABLE)

    /** one-shot 只读提示行（明确不可续聊；加载中/失败不显示——静默保守隐藏）。 */
    fun readOnlyHintVisible(parentSessionId: String?, serverType: ServerType, mode: String?): Boolean =
        parentSessionId != null &&
            serverType == ServerType.Dsh &&
            mode == SubagentCatalogEntry.MODE_ONE_SHOT
}
