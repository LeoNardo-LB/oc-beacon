package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * A user-defined category for grouping sessions.
 *
 * Persisted as JSON in DataStore Preferences. [color] and [icon] are string keys
 * resolved to [androidx.compose.ui.graphics.Color] / [androidx.compose.ui.graphics.vector.ImageVector]
 * via [dev.leonardo.ocbeacon.ui.screens.sessions.components.SessionCategoryStyle].
 */
@Serializable
data class SessionCategory(
    val id: String,
    val name: String,
    val color: String = "blue",
    val icon: String = "folder",
)
