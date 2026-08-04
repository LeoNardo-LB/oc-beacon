package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Part

/**
 * 在 FileViewer 中查看工具文件快照的请求（规范 §5.1-5.4）。
 *
 * 用户点击 ↗ 时由 Read/Write/Edit 工具卡片创建。
 * 直接携带 [part]，使 NavGraph 无需查找消息状态即可缓存快照。
 */
data class ViewToolRequest(
    val filePath: String,
    val source: String,
    val part: Part.Tool
)
