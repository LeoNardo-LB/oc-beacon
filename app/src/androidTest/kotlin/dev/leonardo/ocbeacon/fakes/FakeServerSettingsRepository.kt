package dev.leonardo.ocbeacon.fakes

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
import dev.leonardo.ocbeacon.domain.repository.ServerSettingsRepository
import javax.inject.Inject

/**
 * ServerSettingsRepository 测试 fake（2026-09-17 补）。
 *
 * 背景：DomainModule 增加了 bindServerSettingsRepository，而 FakeDomainModule
 * （@TestInstallIn replaces DomainModule）未同步提供该接口 → Hilt 测试组件
 * MissingBinding → androidTest 插桩图整体编译失败。与 2026-08-16 补
 * MessageCacheRepository 同类缺口（测试图随主图演进未同步）。
 *
 * 语义：全部读写按「部署未挂插件 / 读取失败」的静默降级档返回
 * （null / false / 空集合），不抛 [dev.leonardo.ocbeacon.domain.repository.DshSettingsForbiddenException]。
 */
class FakeServerSettingsRepository @Inject constructor() : ServerSettingsRepository {

    override suspend fun getPermissionDefault(conn: ServerConnection): DshPermissionDefault? = null

    override suspend fun setPermissionDefault(conn: ServerConnection, preset: String): Boolean = false

    override suspend fun getDefaultAgentPreset(conn: ServerConnection): DshAgentPresetDefault? = null

    override suspend fun setDefaultAgentPreset(conn: ServerConnection, preset: String): Boolean = false

    override suspend fun describeSettingsForms(conn: ServerConnection): List<DshSettingsNamespaceForm>? = null

    override suspend fun mutateSettings(
        conn: ServerConnection,
        ns: String,
        ops: List<DshSettingsOp>,
        expectedRevision: Long,
    ): Boolean = false

    override suspend fun setSecret(conn: ServerConnection, ref: String, value: String): Boolean = false

    override suspend fun listPluginInventory(conn: ServerConnection): DshPluginInventory? = null

    override suspend fun agentPresetRoster(conn: ServerConnection): DshAgentPresetRoster =
        DshAgentPresetRoster()

    override suspend fun readAgentPreset(conn: ServerConnection, id: String): DshAgentPresetDocument? = null

    override suspend fun copyAgentPreset(
        conn: ServerConnection,
        from: String,
        id: String,
        name: String?,
    ): Boolean = false

    override suspend fun deleteAgentPreset(conn: ServerConnection, id: String): Boolean = false

    override suspend fun listProviderDirectory(conn: ServerConnection): List<DshProviderDirectoryEntry> =
        emptyList()

    override suspend fun discoverModels(
        conn: ServerConnection,
        request: DshModelDiscoveryRequest,
    ): List<DshDiscoveredModel> = emptyList()

    override suspend fun createCustomProvider(
        conn: ServerConnection,
        draft: DshCustomProviderDraft,
    ): Boolean = false

    override suspend fun deleteCustomProvider(conn: ServerConnection, route: String): Boolean = false
}
