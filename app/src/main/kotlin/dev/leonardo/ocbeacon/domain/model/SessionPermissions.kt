package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * DSH 会话权限预设状态（permissions 投影 / 三 knob 事件折叠产物）。
 *
 * 来源（三源交叉，见 docs/research/2026-08-31-dsh-permission-sandbox-approval.md）：
 * - 读：session.list / session.history 尾页 projections.values.permissions =
 *   {options:[{value,name,description?}],currentValue}（DshSessionMapper 解析）；
 * - 写：POST /api/commands/execute 的 /permission <preset> 命令（DshApiClient）；
 * - 事件：permission/preset {preset} / sandbox/mode {mode} / approval/policy {policy}
 *   三帧（DshEventMapper 映射，更新本状态 currentValue/sandboxMode/approvalPolicy）。
 *
 * currentValue 是服务端派生的「当前档」：命中预设表 = 档名；否则 = "custom"（非预设
 * 派生态，只显示不可切）。options 即部署预设表（本部署 3 档 read-only / workspace-write /
 * danger-full-access，档数由部署决定、客户端按 options 动态渲染）；服务端在派生 custom 时
 * 会额外 append 一个 value="custom" 的伪选项，switchableOptions 据此过滤。
 */
@Serializable
data class SessionPermissions(
    /** 可切换预设选项（声明序）。 */
    val options: List<PermissionPresetOption> = emptyList(),
    /** 当前派生档名（预设名或 "custom"）。 */
    val currentValue: String? = null,
    /** 最近一次 sandbox/mode 载荷（read-only / workspace-write / danger-full-access）。 */
    val sandboxMode: String? = null,
    /** 最近一次 approval/policy 载荷（ask / never）。 */
    val approvalPolicy: String? = null,
) {
    @Serializable
    data class PermissionPresetOption(
        val value: String,
        val name: String,
        val description: String? = null,
    )

    /** 可切换预设（排除服务端派生的 "custom" 伪选项——它永远不是切换目标）。 */
    val switchableOptions: List<PermissionPresetOption>
        get() = options.filter { it.value != "custom" }

    /**
     * 自定义态判定：当前值不落在可切换预设内（或显式 "custom"）→ true。
     * options 尚未加载（空）时不误判（事件早于投影基线到达的竞态窗口）。
     */
    val isCustom: Boolean
        get() = when {
            currentValue == "custom" -> true
            currentValue == null -> false
            switchableOptions.isEmpty() -> false
            else -> switchableOptions.none { it.value == currentValue }
        }
}
