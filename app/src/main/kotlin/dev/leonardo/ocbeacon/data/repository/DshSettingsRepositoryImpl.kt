package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.api.dsh.DshApiClient
import dev.leonardo.ocbeacon.domain.model.DshAgentPresetDefault
import dev.leonardo.ocbeacon.domain.model.DshAgentPresetDocument
import dev.leonardo.ocbeacon.domain.model.DshAgentPresetRoster
import dev.leonardo.ocbeacon.domain.model.DshCustomProviderDraft
import dev.leonardo.ocbeacon.domain.model.DshCustomProviders
import dev.leonardo.ocbeacon.domain.model.DshDiscoveredModel
import dev.leonardo.ocbeacon.domain.model.DshModelDiscoveryRequest
import dev.leonardo.ocbeacon.domain.model.DshPermissionDefault
import dev.leonardo.ocbeacon.domain.model.DshPluginInventory
import dev.leonardo.ocbeacon.domain.model.DshProviderDirectoryEntry
import dev.leonardo.ocbeacon.domain.model.DshSettingsFormMapper
import dev.leonardo.ocbeacon.domain.model.DshSettingsNamespaceForm
import dev.leonardo.ocbeacon.domain.model.DshSettingsOp
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.repository.DshSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DshSettingsRepositoryImpl @Inject constructor(
    private val dshApi: DshApiClient,
) : DshSettingsRepository {

    override suspend fun getPermissionDefault(conn: ServerConnection): DshPermissionDefault? =
        dshApi.getPermissionDefault(conn)

    override suspend fun setPermissionDefault(conn: ServerConnection, preset: String): Boolean =
        dshApi.setPermissionDefault(conn, preset)

    override suspend fun getDefaultAgentPreset(conn: ServerConnection): DshAgentPresetDefault? =
        dshApi.getDefaultAgentPreset(conn)

    override suspend fun setDefaultAgentPreset(conn: ServerConnection, preset: String): Boolean =
        dshApi.setDefaultAgentPreset(conn, preset)

    // ============ #324④ 插件配置与清单 ============

    override suspend fun describeSettingsForms(conn: ServerConnection): List<DshSettingsNamespaceForm>? {
        val snapshot = dshApi.describeSettings(conn) ?: return null
        return snapshot.namespaces.mapNotNull { ns ->
            DshSettingsFormMapper.map(ns, nsWritable = snapshot.writable)
        }
    }

    override suspend fun mutateSettings(conn: ServerConnection, ns: String, ops: List<DshSettingsOp>, expectedRevision: Long): Boolean =
        dshApi.mutateSettings(conn, ns, ops, expectedRevision)

    override suspend fun setSecret(conn: ServerConnection, ref: String, value: String): Boolean =
        dshApi.setCredential(conn, ref, value)

    override suspend fun listPluginInventory(conn: ServerConnection): DshPluginInventory? =
        dshApi.listPluginInventory(conn)

    // ============ #324② preset 管理 ============

    override suspend fun agentPresetRoster(conn: ServerConnection): DshAgentPresetRoster =
        dshApi.agentPresetRoster(conn)

    override suspend fun readAgentPreset(conn: ServerConnection, id: String): DshAgentPresetDocument? =
        dshApi.readAgentPreset(conn, id)

    override suspend fun copyAgentPreset(conn: ServerConnection, from: String, id: String, name: String?): Boolean =
        dshApi.copyAgentPreset(conn, from, id, name)

    override suspend fun deleteAgentPreset(conn: ServerConnection, id: String): Boolean =
        dshApi.deleteAgentPreset(conn, id)

    // ============ #324① provider/模型目录 ============

    override suspend fun listProviderDirectory(conn: ServerConnection): List<DshProviderDirectoryEntry> {
        val registered = dshApi.getProviders(conn).providers.map { it.id to it.name }
        val configurable = dshApi.listConfigurableProviders(conn)
        val rows = DshCustomProviders.joinDirectory(registered, configurable)
        // 自定义行（llm-pi-ai ns 下 providers 路径）+ 凭据 ref（settings 值 apiKeyEnv 优先，派生兜底）
        val nsValue = dshApi.settingsNamespaceSnapshot(conn, DshCustomProviders.SETTINGS_NS)
            ?.get("value") as? kotlinx.serialization.json.JsonObject
        val providersValue = nsValue?.get("providers") as? kotlinx.serialization.json.JsonObject
        return rows.map { row ->
            val isCustom = row.settingsNs == DshCustomProviders.SETTINGS_NS &&
                row.settingsPath.firstOrNull() == "providers" && row.settingsPath.size == 2
            val credential = if (isCustom) {
                val profileRef = (providersValue?.get(row.provider) as? kotlinx.serialization.json.JsonObject)
                    ?.let { ((it["apiKeyEnv"] as? kotlinx.serialization.json.JsonPrimitive)?.content) }
                val ref = profileRef ?: DshCustomProviders.deriveCredentialRef(row.provider)
                dshApi.describeCredentials(conn, listOf(ref))[ref]
            } else null
            DshProviderDirectoryEntry(row = row, isCustom = isCustom, credential = credential)
        }
    }

    override suspend fun discoverModels(conn: ServerConnection, request: DshModelDiscoveryRequest): List<DshDiscoveredModel> =
        dshApi.discoverModels(conn, request)

    override suspend fun createCustomProvider(conn: ServerConnection, draft: DshCustomProviderDraft): Boolean {
        // 乐观并发 revision：先行 describe（失败/缺席 → null = 不带 revision 盲写）
        val revision = dshApi.settingsNamespaceSnapshot(conn, DshCustomProviders.SETTINGS_NS)
            ?.dshRevision()
        return dshApi.createCustomProvider(conn, draft, expectedRevision = revision)
    }

    override suspend fun deleteCustomProvider(conn: ServerConnection, route: String): Boolean {
        val nsSnapshot = dshApi.settingsNamespaceSnapshot(conn, DshCustomProviders.SETTINGS_NS)
        val nsValue = nsSnapshot?.get("value") as? kotlinx.serialization.json.JsonObject
        val providersMap = nsValue?.get("providers") as? kotlinx.serialization.json.JsonObject
        val profile = providersMap?.get(route) as? kotlinx.serialization.json.JsonObject
        val profileRef = profile
            ?.get("apiKeyEnv")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        // 托管凭据判定（对齐 web targetOf）：profile 命名 ref 且已配置可写才连带清除，
        // 否则只删 settings 段（派生 ref 未被 profile 引用时不动——可能是别家凭据）
        val credentialRef = profileRef?.let { ref ->
            dshApi.describeCredentials(conn, listOf(ref))[ref]
                ?.takeIf { it.configured && it.writable }?.let { ref }
        }
        return dshApi.deleteCustomProvider(
            conn,
            settingsNs = DshCustomProviders.SETTINGS_NS,
            settingsPath = DshCustomProviders.providerPath(route),
            credentialRef = credentialRef,
        )
    }
}

/** namespace 快照 revision 提取（describe/mutate 回程同形）。 */
private fun kotlinx.serialization.json.JsonObject.dshRevision(): Long? =
    ((this["revision"] as? kotlinx.serialization.json.JsonPrimitive)?.content)?.toLongOrNull()
