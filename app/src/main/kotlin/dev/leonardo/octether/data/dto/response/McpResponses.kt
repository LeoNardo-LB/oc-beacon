package dev.leonardo.octether.data.dto.response

import kotlinx.serialization.Serializable

@Serializable
data class McpStatusEntry(
    val status: String  // connected | disabled | failed | needs_auth | needs_client_registration
)

@Serializable
data class McpServerConfig(
    val type: String? = null,
    val command: List<String>? = null,
    val enabled: Boolean = true,
    val url: String? = null,
    val environment: Map<String, String>? = null,
    val headers: Map<String, String>? = null
)
