package dev.leonardo.octether.domain.model

data class McpServerStatus(
    val name: String,
    val type: String,         // "local" | "remote"
    val status: String,       // connected | disabled | failed | needs_auth | needs_client_registration
    val command: List<String>? = null,  // local type
    val url: String? = null,            // remote type
)
