package dev.leonardo.ocbeacon.domain.model

data class PermissionState(
    val id: String,
    val sessionId: String,
    val permission: String,
    val patterns: List<String> = emptyList(),
    val metadata: Map<String, String>? = null,
    val always: Boolean = false,
    val tool: ToolRef? = null
)
