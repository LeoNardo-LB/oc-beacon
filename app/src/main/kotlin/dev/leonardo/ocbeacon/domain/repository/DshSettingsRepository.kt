package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.DshAgentPresetDefault
import dev.leonardo.ocbeacon.domain.model.DshPermissionDefault
import dev.leonardo.ocbeacon.domain.model.ServerConnection

/**
 * DSH 新会话默认权限档（settings.describe / settings.mutate ns=permission）读写缝隙。
 * 显式传 conn（McpRepository 先例——多服务器并发下共享可变 connection 互相覆盖）。
 * 仅 DSH 有意义；OpenCode V1/V2 无 settings.mutate 域，UI 按能力位隐藏。
 */
interface DshSettingsRepository {
    /** 读当前默认档；部署未挂 permission 插件或读取失败 → null。 */
    suspend fun getPermissionDefault(conn: ServerConnection): DshPermissionDefault?

    /** 写默认档（内部先 describe 取 revision 再 mutate，乐观并发）。 */
    suspend fun setPermissionDefault(conn: ServerConnection, preset: String): Boolean

    /** 读新会话默认 Agent 预设；部署未挂 agent-presets 插件或读取失败 → null。 */
    suspend fun getDefaultAgentPreset(conn: ServerConnection): DshAgentPresetDefault?

    /** 写新会话默认 Agent 预设（内部先 describe 取 revision 再 mutate，乐观并发）。 */
    suspend fun setDefaultAgentPreset(conn: ServerConnection, preset: String): Boolean
}
