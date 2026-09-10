package dev.leonardo.ocbeacon.ui.extension

import androidx.compose.runtime.Composable
import dev.leonardo.ocbeacon.domain.model.ServerCapabilities
import dev.leonardo.ocbeacon.domain.model.ServerUiSlot
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 界面插槽注册表（#391 切片5）——界面层持有，按槽位聚合各私有界面扩展。
 *
 * 注册：每个扩展在自己包内以 @IntoSet 多绑定贡献；本类按 [ServerUiSlot] 分组。
 * 渲染：[Render] 只做「按能力过滤 -> 排序 -> 交给贡献方渲染」——不含任何服务器类型分支。
 */
@Singleton
class ServerUiSlotRegistry @Inject constructor(
    extensions: Set<@JvmSuppressWildcards ServerUiExtension>,
) {

    private val bySlot: Map<ServerUiSlot, List<ServerUiExtension>> =
        extensions.groupBy { it.slot }

    /** 已注册的槽位（诊断 / 测试用）。 */
    fun registeredSlots(): Set<ServerUiSlot> = bySlot.keys

    /** 某槽位在当前能力位下的有序贡献列表（非 Composable，便于测试）。 */
    fun contributions(slot: ServerUiSlot, caps: ServerCapabilities): List<ServerUiExtension> =
        bySlot[slot].orEmpty()
            .filter { it.isEnabled(caps) }
            .sortedBy { it.order }

    /** 渲染某槽位的全部贡献。宿主类型不匹配由贡献方显式失败（见 ServerUiExtension）。 */
    @Composable
    fun Render(slot: ServerUiSlot, caps: ServerCapabilities, host: ServerUiSlotHost) {
        contributions(slot, caps).forEach { it.Content(host) }
    }
}
