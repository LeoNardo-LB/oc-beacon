package dev.leonardo.ocbeacon.ui.screens.sessions.components

/**
 * #311 Task2：会话行长按菜单可见项纯函数（显隐逻辑单测消费）。
 *
 * 契约事实（2026-09-05 四重取证）：DSH workspace/archiveSession 为幂等
 * add-only——服务端 archivedSessionIds 无任何移除路径（全包 grep 无 unarchive，
 * 官方 web 客户端同无恢复入口），**归档单向**。故已归档行菜单只留「详情」，
 * 不存在「取消归档」项；后续勿在无 wire 动词时误加恢复入口。
 *
 * @param archiveSupported 服务器能力位（DSH=true；非 DSH 归档面整体隐藏）
 * @param isArchived 该会话是否在 workspace 快照归档集合内
 */
internal enum class SessionRowMenuAction { DETAILS, RENAME, ARCHIVE }

internal fun sessionRowMenuActions(
    archiveSupported: Boolean,
    isArchived: Boolean,
): List<SessionRowMenuAction> = if (isArchived) {
    // 已归档：无恢复动作可用（归档单向契约）——如实只呈现详情
    listOf(SessionRowMenuAction.DETAILS)
} else {
    buildList {
        add(SessionRowMenuAction.DETAILS)
        add(SessionRowMenuAction.RENAME)
        if (archiveSupported) add(SessionRowMenuAction.ARCHIVE)
    }
}
