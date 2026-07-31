package dev.leonardo.octether.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * Semantic padding tokens for [androidx.compose.material3.ListItem] content.
 * Three density levels matching the project's token system pattern.
 */
object ListItemTokens {
    /** Compact — minimal vertical padding. */
    val ContentPaddingSmall = PaddingValues(horizontal = 16.dp, vertical = 2.dp)
    /** Medium — balanced density (default for settings-style lists). */
    val ContentPaddingMedium = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
    /** Large — Material 3 default density. */
    val ContentPaddingLarge = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
}
