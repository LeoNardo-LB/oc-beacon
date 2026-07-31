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
 * Visual style mapping for [dev.leonardo.ocbeacon.domain.model.SessionCategory].
 *
 * Color and icon are stored as string keys (for JSON persistence) and resolved
 * here to Compose types. Unknown keys fall back to safe defaults.
 */
object SessionCategoryStyle {

    /** All selectable color keys, in picker display order. */
    val colorKeys: List<String> = listOf(
        "red", "orange", "amber", "green", "teal", "blue", "purple", "pink"
    )

    /** All selectable icon keys, in picker display order. */
    val iconKeys: List<String> = listOf(
        "folder", "code", "terminal", "bug", "build", "science", "lightbulb", "star", "bookmark", "label"
    )

    /** Resolve a color key to a [Color]. Falls back to blue. */
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

    /** Resolve an icon key to an [ImageVector]. Falls back to folder. */
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

    // Material 400 shades — vibrant but readable on both light/dark surfaces.
    private val Red = Color(0xFFEF5350)
    private val Orange = Color(0xFFFFA726)
    private val Amber = Color(0xFFFFCA28)
    private val Green = Color(0xFF66BB6A)
    private val Teal = Color(0xFF26A69A)
    private val Blue = Color(0xFF42A5F5)
    private val Purple = Color(0xFFAB47BC)
    private val Pink = Color(0xFFEC407A)
}
