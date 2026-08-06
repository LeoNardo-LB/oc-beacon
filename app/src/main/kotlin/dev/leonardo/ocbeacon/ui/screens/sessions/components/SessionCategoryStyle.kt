package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 标签/分类的视觉样式映射。
 *
 * 颜色和图标以字符串键存储（用于 JSON 持久化），在此处解析为
 * Compose 类型。未知键回退到安全的默认值。
 */
object SessionCategoryStyle {

    /** 所有可选的颜色键，按选择器显示顺序排列。 */
    val colorKeys: List<String> = listOf(
        "red", "orange", "amber", "green", "teal", "blue", "purple", "pink"
    )

    /** 所有可选的图标键，按选择器显示顺序排列。 */
    val iconKeys: List<String> = listOf(
        "folder", "code", "terminal", "bug", "build", "science", "lightbulb", "star", "bookmark", "label"
    )

    /** 将颜色键解析为 [Color]。未知键回退到蓝色。 */
    fun color(key: String): Color = when (key) {
        "red" -> Red
        "orange" -> Orange
        "amber" -> Amber
        "green" -> Green
        "teal" -> Teal
        "purple" -> Purple
        "pink" -> Pink
        else -> Blue
    }

    /** 将图标键解析为 [ImageVector]。未知键回退到 folder。 */
    fun icon(key: String): ImageVector = when (key) {
        "code" -> Icons.Filled.Code
        "terminal" -> Icons.Filled.Terminal
        "bug" -> Icons.Filled.BugReport
        "build" -> Icons.Filled.Build
        "science" -> Icons.Filled.Science
        "lightbulb" -> Icons.Filled.Lightbulb
        "star" -> Icons.Filled.Star
        "bookmark" -> Icons.Filled.Bookmark
        "label" -> Icons.AutoMirrored.Filled.Label
        else -> Icons.Filled.Folder
    }

    // Material 400 色调 — 鲜艳但同时在浅/深背景上都可读。
    private val Red = Color(0xFFEF5350)
    private val Orange = Color(0xFFFFA726)
    private val Amber = Color(0xFFFFCA28)
    private val Green = Color(0xFF66BB6A)
    private val Teal = Color(0xFF26A69A)
    private val Blue = Color(0xFF42A5F5)
    private val Purple = Color(0xFFAB47BC)
    private val Pink = Color(0xFFEC407A)
}
