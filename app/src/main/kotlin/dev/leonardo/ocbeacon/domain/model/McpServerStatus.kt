package dev.leonardo.ocbeacon.domain.model

data class McpServerStatus(
    val name: String,
    val type: String,         // "local" | "remote"
    val status: String,       // connected | disabled | failed | needs_auth | needs_client_registration
    val command: List<String>? = null,  // local 类型
    val url: String? = null,            // remote 类型
)
