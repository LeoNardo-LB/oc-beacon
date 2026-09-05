package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * #324① DSH 可配置 provider 目录条目（llm/listConfigurableProviders 回程行）。
 *
 * 契约锚点（dsh-llm typert）：{provider, displayName, settingsNs, settingsPath[], declared?}。
 * settingsNs/settingsPath 是该 provider 配置在 settings 域的写地址（增删经 settings/mutate）。
 */
@Serializable
data class DshConfigurableProvider(
    val provider: String,
    val displayName: String,
    val settingsNs: String = "",
    val settingsPath: List<String> = emptyList(),
    val declared: Boolean? = null,
)

/**
 * 目录合流行（llm/listProviders × llm/listConfigurableProviders，对齐 web
 * joinProviderDirectory）：active = 已注册到 llm 运行时；settingsNs 空串 =
 * 运行时注册但不可经设置面管理（内置/环境变量来源）。
 */
@Serializable
data class DshProviderDirectoryRow(
    val provider: String,
    val displayName: String,
    val settingsNs: String = "",
    val settingsPath: List<String> = emptyList(),
    /** 已注册到 llm 运行时（llm/listProviders 命中）。 */
    val active: Boolean = false,
    val declared: Boolean? = null,
)

/** llm/discoverModels 回程行（{id, name?, contextWindow?, maxTokens?}）。 */
@Serializable
data class DshDiscoveredModel(
    val id: String,
    val name: String? = null,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
)

/**
 * 目录条目（合流行 + 移动端管理事实）：isCustom = llm-pi-ai settingsPath 下的
 * 手工声明路由（可删）；credential 非空 = 凭据状态可查（**仅 configured 态，无明文**）。
 */
@Serializable
data class DshProviderDirectoryEntry(
    val row: DshProviderDirectoryRow,
    val isCustom: Boolean = false,
    val credential: DshCredentialStatus? = null,
)

/** llm/discoverModels 请求（provider/baseURL/api/apiKey 全可选——探测式拉取）。 */
@Serializable
data class DshModelDiscoveryRequest(
    val settingsNs: String,
    val provider: String? = null,
    val baseURL: String? = null,
    val api: String? = null,
    val apiKey: String? = null,
)

/** credentials/describe 回程行（configured/source?/writable；**永不携带明文**）。 */
@Serializable
data class DshCredentialStatus(
    val ref: String,
    val configured: Boolean,
    val writable: Boolean,
    val source: String? = null,
)

/** 自定义 provider 新建表单数据（settings 写 + 可选凭据写）。 */
@Serializable
data class DshCustomProviderDraft(
    val route: String,
    val displayName: String,
    val baseURL: String,
    val api: String,
    /** 空串 = 不存凭据（profile 也不写 apiKeyEnv）。 */
    val apiKey: String,
    val models: List<DshDiscoveredModel>,
)

/**
 * #324① 自定义 provider 纯逻辑（契约锚点：web dsh-client-ui-settings-models
 * CustomProviderCard + dsh-llm-pi-ai PROTOCOLS/Config schema）。
 *
 * - NS/路径：settings ns="llm-pi-ai"，path=["providers",route]；
 * - profile：{displayName?, apiKeyEnv?, api, baseURL, models[{id,name?,contextWindow?,maxTokens?}]}；
 * - 凭据 ref：route 大写、非字母数字段折叠为 "_"、后缀 _API_KEY（POSIX 标识符，
 *   首字母必须字母 → route pattern 同源约束）；
 * - 协议表序（首项即默认）：openai-completions/openai-responses/anthropic-messages。
 */
object DshCustomProviders {

    const val SETTINGS_NS = "llm-pi-ai"

    val PROTOCOLS: List<String> = listOf(
        "openai-completions",
        "openai-responses",
        "anthropic-messages",
    )

    private val ROUTE_PATTERN = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")

    /** route 合法性（小写字母开头 + 数字/连字符组；凭据 ref POSIX 约束同源）。 */
    fun isValidRoute(route: String): Boolean = ROUTE_PATTERN.matches(route)

    /** 凭据 ref 派生（route → ACME_GATEWAY_API_KEY 形态）。 */
    fun deriveCredentialRef(route: String): String =
        route.uppercase().replace(Regex("[^A-Z0-9]+"), "_") + "_API_KEY"

    /** settings 写地址：["providers", route]。 */
    fun providerPath(route: String): List<String> = listOf("providers", route)

    /** 表单 → profile JSON（displayName/apiKeyEnv 空则缺席；模型条目仅携带已知字段）。 */
    fun profileJson(draft: DshCustomProviderDraft): JsonObject = buildJsonObject {
        if (draft.displayName.isNotBlank()) put("displayName", draft.displayName)
        if (draft.apiKey.isNotBlank()) put("apiKeyEnv", deriveCredentialRef(draft.route))
        put("api", draft.api)
        put("baseURL", draft.baseURL)
        put("models", JsonArray(draft.models.map { modelEntry(it) }))
    }

    /** 发现模型 → settings 模型条目（id 必带；可选字段缺席由服务端按 catalog 缺省继承）。 */
    fun modelEntry(model: DshDiscoveredModel): JsonObject = buildJsonObject {
        put("id", model.id)
        model.name?.let { put("name", it) }
        model.contextWindow?.let { put("contextWindow", it) }
        model.maxTokens?.let { put("maxTokens", it) }
    }

    /**
     * 目录合流（对齐 web joinProviderDirectory）：目录序优先，settings 地址随行；
     * 已注册但不在目录的 provider 防御性追加（settingsNs 空 = 不可设置面管理）。
     */
    fun joinDirectory(
        registered: List<Pair<String, String>>,
        configurable: List<DshConfigurableProvider>,
    ): List<DshProviderDirectoryRow> {
        val active = registered.toMap()
        val declaredIds = configurable.map { it.provider }.toSet()
        val rows = configurable.map { entry ->
            DshProviderDirectoryRow(
                provider = entry.provider,
                displayName = entry.displayName,
                settingsNs = entry.settingsNs,
                settingsPath = entry.settingsPath,
                active = active.containsKey(entry.provider),
                declared = entry.declared,
            )
        }.toMutableList()
        for ((id, name) in registered) {
            if (id in declaredIds) continue
            rows += DshProviderDirectoryRow(provider = id, displayName = name)
        }
        return rows
    }
}
