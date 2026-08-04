package dev.leonardo.ocbeacon.domain.model

/**
 * OpenCode 全局服务器配置（对应 GET /global/config）。
 *
 * 注意：这与 [ServerConfig]（存储的连接配置）是不同概念。
 * 此类型表示远程服务器上 OpenCode 实例的运行时配置。
 */
data class GlobalConfig(
    val disabledProviders: List<String> = emptyList(),
    val enabledProviders: List<String>? = null,
    val model: String? = null,
    val smallModel: String? = null,
    val defaultAgent: String? = null,
)

/**
 * 对 [GlobalConfig] 的局部修补请求（对应 PATCH /global/config）。
 * null 字段表示"不修改"。
 */
data class GlobalConfigPatch(
    val disabledProviders: List<String>? = null,
    val model: String? = null,
    val smallModel: String? = null,
    val defaultAgent: String? = null,
)

/**
 * Provider 支持的认证方法。
 * 对应 data.dto.response.ProviderAuthMethod。
 */
data class ProviderAuthMethod(
    val type: String,
    val label: String,
)

/**
 * OAuth 授权流程的启动结果。
 * 对应 data.dto.response.ProviderOauthAuthorization。
 */
data class ProviderOauthAuthorization(
    val url: String = "",
    val method: String = "none",
    val instructions: String = "",
)

/**
 * Provider 连接状态目录（对应 GET /provider）。
 * 包含所有 provider 的完整信息及当前已连接的 provider ID 集合。
 */
data class ProviderConnectionStatus(
    val providers: List<ProviderCatalog>,
    val connected: Set<String>,
)
