package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * A saved permission auto-approve rule.
 * When an incoming [SseEvent.PermissionAsked] matches [toolName] + [sessionId] + [directoryPattern],
 * the permission is auto-approved.
 */
@Serializable
data class AutoApproveRule(
    val toolName: String,
    val sessionId: String? = null,
    val directoryPattern: String = "*",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun matches(event: SseEvent.PermissionAsked, sessionDirectory: String): Boolean {
        // Tool name must match (exact or wildcard)
        if (toolName != "*" && event.permission != toolName) return false

        // Session must match if specified
        if (sessionId != null && event.sessionId != sessionId) return false

        // Directory pattern must match
        if (directoryPattern != "*" && directoryPattern != sessionDirectory) return false

        return true
    }
}
