package dev.leonardo.ocbeacon.ui.screens.sessions.dsh

import androidx.compose.runtime.Composable
import dev.leonardo.ocbeacon.domain.model.ServerCapabilities
import dev.leonardo.ocbeacon.domain.model.ServerFeatures
import dev.leonardo.ocbeacon.domain.model.ServerUiSlot
import dev.leonardo.ocbeacon.ui.components.dsh.DshTokenNeededBanner
import dev.leonardo.ocbeacon.ui.extension.SessionListHeaderSlotHost
import dev.leonardo.ocbeacon.ui.extension.ServerUiExtension
import dev.leonardo.ocbeacon.ui.extension.ServerUiSlotHost
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DSH token 待输入横幅（#391 切片9 迁移自 SessionListScreen 的 dshTokenNeeded 硬嵌分支）。
 *
 * 挂载：SESSION_LIST_HEADER 槽位；是否出现只读宿主的 tokenNeeded（DSH 传输层状态），
 * 通用屏幕不再认识具体横幅实现。凭据输入出路由宿主回调。
 */
@Singleton
class DshTokenBannerExtension @Inject constructor() : ServerUiExtension {

    override val slot: ServerUiSlot = ServerUiSlot.SESSION_LIST_HEADER

    override val order: Int = 100

    /** token/cookie 凭据式鉴权（OpenCode 基本面认证不走此插槽）。 */
    override fun isEnabled(caps: ServerCapabilities): Boolean =
        ServerFeatures.AUTH_TOKEN in caps

    @Composable
    override fun Content(host: ServerUiSlotHost) {
        require(host is SessionListHeaderSlotHost) {
            "SESSION_LIST_HEADER 槽位收到不匹配的宿主: " + host::class.simpleName
        }
        if (host.tokenNeeded) {
            DshTokenNeededBanner(onEnterToken = host.onEnterToken)
        }
    }
}
