package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * 已保存的权限自动批准规则。
 * 当收到的 [SseEvent.PermissionAsked] 匹配 [toolName] 与 [sessionId] + [directoryPattern] 时（[PermissionAsked] 无独立 toolName 字段，匹配基于 permission 字段——该字段承载权限/工具名字符串），
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
        // 新增P2（2026-08-19）：空名防御——历史遗留的空 toolName 规则/空名事件
        // 互相匹配是伪命中（无语义），双端任一为空即不匹配
        if (toolName.isBlank() || event.permission.isBlank()) return false

        // 权限/工具名必须匹配（精确匹配或通配符；比较 event.permission）
        if (toolName != "*" && event.permission != toolName) return false

        // 若指定了会话，则会话必须匹配
        if (sessionId != null && event.sessionId != sessionId) return false

        // 目录模式必须匹配——#308 回修 Layer1：**会话锚定规则（sessionId 非空且
        // 命中）免目录检查**——运行期新建会话的 cwd 回填与 session-added 帧存在
        // 竞态（handler 目录可为空，approver 解析空目录致规则静默不命中）；
        // 会话身份是比目录更强的锚，savePermissionRule 现恒带 sessionId。
        if (sessionId == null &&
            directoryPattern != "*" && directoryPattern != sessionDirectory
        ) return false

        return true
    }
}
