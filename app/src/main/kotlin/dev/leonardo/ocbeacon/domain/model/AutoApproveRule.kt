package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * 已保存的权限自动批准规则。
 * 当收到的 [SseEvent.PermissionAsked] 匹配 [toolName] + [sessionId] + [directoryPattern] 时，
 * 该权限将被自动批准。
 */
@Serializable
data class AutoApproveRule(
    val toolName: String,
    val sessionId: String? = null,
    val directoryPattern: String = "*",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun matches(event: SseEvent.PermissionAsked, sessionDirectory: String): Boolean {
        // 工具名必须匹配（精确匹配或通配符）
        if (toolName != "*" && event.permission != toolName) return false

        // 若指定了会话，则会话必须匹配
        if (sessionId != null && event.sessionId != sessionId) return false

        // 目录模式必须匹配
        if (directoryPattern != "*" && directoryPattern != sessionDirectory) return false

        return true
    }
}
