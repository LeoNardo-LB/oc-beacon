package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.DshAgentPresetDefault
import dev.leonardo.ocbeacon.domain.model.DshAgentPresetDocument
import dev.leonardo.ocbeacon.domain.model.DshAgentPresetRoster
import dev.leonardo.ocbeacon.domain.model.DshCustomProviderDraft
import dev.leonardo.ocbeacon.domain.model.DshDiscoveredModel
import dev.leonardo.ocbeacon.domain.model.DshModelDiscoveryRequest
import dev.leonardo.ocbeacon.domain.model.DshPermissionDefault
import dev.leonardo.ocbeacon.domain.model.DshPluginInventory
import dev.leonardo.ocbeacon.domain.model.DshProviderDirectoryEntry
import dev.leonardo.ocbeacon.domain.model.DshSettingsNamespaceForm
import dev.leonardo.ocbeacon.domain.model.DshSettingsOp
import dev.leonardo.ocbeacon.domain.model.ServerConnection

/**
 * settings.* 特权面被服务端 403 拒（#298：DSH PRIVILEGED_METHODS 硬门禁只放行
 * loopback Host——非 loopback 连接（LAN IP/Tailscale）读写默认档恒 403）。
 * UI 据此显示「需 loopback 连接（adb reverse）」标注，勿当普通失败静默降级。
 */
class DshSettingsForbiddenException(
    message: String = "settings.* privileged methods forbidden (403; loopback-only)",
) : RuntimeException(message)

/**
 * DSH 新会话默认权限档（settings.describe / settings.mutate ns=permission）读写缝隙。
 * 显式传 conn（McpRepository 先例——多服务器并发下共享可变 connection 互相覆盖）。
 * 仅 DSH 有意义；OpenCode V1/V2 无 settings.mutate 域，UI 按能力位隐藏。
 *
 * 错误契约：非 loopback 连接（Host 栅栏 403）抛 [DshSettingsForbiddenException]；
 * 其余失败（插件缺席/网络/5xx）维持 null / false 静默降级。
 */
interface DshSettingsRepository {
    /** 读当前默认档；部署未挂 permission 插件或读取失败 → null；403 → 抛 [DshSettingsForbiddenException]。 */
    suspend fun getPermissionDefault(conn: ServerConnection): DshPermissionDefault?

    /** 写默认档（内部先 describe 取 revision 再 mutate，乐观并发）；403 → 抛 [DshSettingsForbiddenException]。 */
    suspend fun setPermissionDefault(conn: ServerConnection, preset: String): Boolean

    /** 读新会话默认 Agent 预设；部署未挂 agent-presets 插件或读取失败 → null；403 → 抛 [DshSettingsForbiddenException]。 */
    suspend fun getDefaultAgentPreset(conn: ServerConnection): DshAgentPresetDefault?

    /** 写新会话默认 Agent 预设（内部先 describe 取 revision 再 mutate，乐观并发）；403 → 抛 [DshSettingsForbiddenException]。 */
    suspend fun setDefaultAgentPreset(conn: ServerConnection, preset: String): Boolean

    // ============ #324④ 插件配置与清单（settings/describe 动态表单 + pluginInventory） ============

    /**
     * settings/describe → 全部 namespace 表单投影（轻量动态表单；顶层标量字段×JSON
     * 类型 + 枚举 + secret 剥离披露）。描述失败 → null；403 → 抛 [DshSettingsForbiddenException]。
     */
    suspend fun describeSettingsForms(conn: ServerConnection): List<DshSettingsNamespaceForm>?

    /** settings/mutate 泛化 ops（乐观并发 revision）。 */
    suspend fun mutateSettings(conn: ServerConnection, ns: String, ops: List<DshSettingsOp>, expectedRevision: Long): Boolean

    /** 凭据写（credentials/set；secret 字段专用通道）。 */
    suspend fun setSecret(conn: ServerConnection, ref: String, value: String): Boolean

    /** pluginInventory/list 清单（只读）；失败 → null。 */
    suspend fun listPluginInventory(conn: ServerConnection): DshPluginInventory?

    // ============ #324② preset 管理（roster 完整面 + read/copy/deletePreset） ============

    /** 完整 roster（presets 含 trust/broken + authorable 可创作位）。 */
    suspend fun agentPresetRoster(conn: ServerConnection): DshAgentPresetRoster

    /** 只读组成文档（未知 id → null）。 */
    suspend fun readAgentPreset(conn: ServerConnection, id: String): DshAgentPresetDocument?

    /** 复制为 user 预设（成功 true）。 */
    suspend fun copyAgentPreset(conn: ServerConnection, from: String, id: String, name: String?): Boolean

    /** 删除 user 预设（成功 true；system 被服务端拒绝）。 */
    suspend fun deleteAgentPreset(conn: ServerConnection, id: String): Boolean

    // ============ #324① provider/模型目录（llm 目录 + credentials + 自定义增删） ============

    /**
     * 目录合流行（llm/listProviders × llm/listConfigurableProviders）+ 自定义行
     * 凭据状态（credentials/describe，仅 configured/writable——**无明文**）。
     * V011 或目录端点缺席 → 仅已注册行（无设置地址）。
     */
    suspend fun listProviderDirectory(conn: ServerConnection): List<DshProviderDirectoryEntry>

    /** llm/discoverModels（端点模型探查；失败上抛由 UI 提示）。 */
    suspend fun discoverModels(conn: ServerConnection, request: DshModelDiscoveryRequest): List<DshDiscoveredModel>

    /** 新建自定义 provider（settings/mutate + credentials/set；403 → 抛 [DshSettingsForbiddenException]）。 */
    suspend fun createCustomProvider(conn: ServerConnection, draft: DshCustomProviderDraft): Boolean

    /** 删除自定义 provider（settings/mutate unset + credentials/unset；403 → 抛 [DshSettingsForbiddenException]）。 */
    suspend fun deleteCustomProvider(conn: ServerConnection, route: String): Boolean
}
