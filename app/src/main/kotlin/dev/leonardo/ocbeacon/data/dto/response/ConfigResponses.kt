package dev.leonardo.ocbeacon.data.dto.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerConfigResponse(
    @SerialName("disabled_providers") val disabledProviders: List<String> = emptyList(),
    @SerialName("enabled_providers") val enabledProviders: List<String>? = null,
    val model: String? = null,
    @SerialName("small_model") val smallModel: String? = null,
    @SerialName("default_agent") val defaultAgent: String? = null,
    val mcp: Map<String, McpServerConfig>? = null
)
