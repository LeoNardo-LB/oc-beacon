package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * 用户定义的会话分组类别。
 *
 * 以 JSON 形式持久化在 DataStore Preferences 中。[color] 和 [icon] 为字符串键，
 * 通过 [dev.leonardo.ocbeacon.ui.screens.sessions.components.SessionCategoryStyle]
 * 解析为 [androidx.compose.ui.graphics.Color] / [androidx.compose.ui.graphics.vector.ImageVector]。
 */
@Serializable
data class SessionCategory(
    val id: String,
    val name: String,
    val color: String = "blue",
    val icon: String = "folder",
)
