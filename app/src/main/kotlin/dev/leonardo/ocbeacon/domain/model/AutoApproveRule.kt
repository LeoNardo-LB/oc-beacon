package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * 已保存的权限自动批准规则。
 * 当收到的 [SseEvent.PermissionAsked] 以 permission 字段（权限/工具名字符串）匹配
 * [toolName] + [sessionId] + [directoryPattern] 时，该权限将被自动批准
 * （PermissionAsked 事件无独立 toolName 字段）。
 */
@Serializable
data class AutoApproveRule(
    val toolName: String,
    val sessionId: String? = null,
    val directoryPattern: String = "*",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun matches(event: SseEvent.PermissionAsked, sessionDirectory: String): Boolean {
        // 新增P2（2026-08-19）：空名防御——历史遗留的空 toolName 规则/空名事件
        // 互相匹配是伪命中（无语义），双端任一为空即不匹配
        if (toolName.isBlank() || event.permission.isBlank()) return false

        // permission 字段必须匹配规则 toolName（精确匹配或通配符）——PermissionAsked 无独立 toolName 字段
        if (toolName != "*" && event.permission != toolName) return false

        // 若指定了会话，则会话必须匹配
        if (sessionId != null && event.sessionId != sessionId) return false

        // 目录模式必须匹配
        if (directoryPattern != "*" && directoryPattern != sessionDirectory) return false

        return true
    }
}
