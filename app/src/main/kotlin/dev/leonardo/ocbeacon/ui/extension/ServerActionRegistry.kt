package dev.leonardo.ocbeacon.ui.extension

import dev.leonardo.ocbeacon.domain.model.ServerCapabilities
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 条目级动作 / 入口贡献注册表（#391 切片9）——与 [ServerUiSlotRegistry] 并列，
 * 共同构成 spec 的「统一贡献注册表（区域插槽 + 条目动作）」。
 *
 * 注册：每条贡献在自己模块内以 @IntoSet 多绑定；本类按表面分组，渲染只做
 * 「按能力过滤 → 排序」——不含任何服务器类型分支。
 */
@Singleton
class ServerActionRegistry @Inject constructor(
    contributions: Set<@JvmSuppressWildcards ServerActionContribution>,
) {

    private val bySurface: Map<ServerActionSurface, List<ServerActionContribution>> =
        contributions.groupBy { it.surface }

    /** 已注册的表面（诊断 / 测试用）。 */
    fun registeredSurfaces(): Set<ServerActionSurface> = bySurface.keys

    /** 某表面在当前能力位下的有序贡献列表（非 Composable，便于测试）。 */
    fun actions(
        surface: ServerActionSurface,
        caps: ServerCapabilities,
    ): List<ServerActionContribution> =
        bySurface[surface].orEmpty()
            .filter { it.isEnabled(caps) }
            .sortedBy { it.order }
}
