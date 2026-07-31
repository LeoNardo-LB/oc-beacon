package dev.leonardo.ocbeacon.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * Standard border used in AMOLED mode: 1dp outlineVariant at 65% opacity.
 */
internal val AmoledDefaultBorder: BorderStroke
    @Composable get() = BorderStroke(
        1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MEDIUM)
    )

/**
 * Card that automatically adapts its appearance for AMOLED dark mode.
 * AMOLED: pure black background + subtle border, no elevation.
 * Normal: uses [normalContainerColor], no border.
 *
 * Replaces the repeating pattern:
 * ```
 * Card(
 *     colors = CardDefaults.cardColors(
 *         containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHighest
 *     ),
 *     border = if (isAmoled) BorderStroke(1.dp, ...) else null,
 * )
 * ```
 */
@Composable
fun AmoledCard(
    isAmoledDark: Boolean,
    modifier: Modifier = Modifier,
    normalContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    shape: Shape = CardDefaults.shape,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (isAmoledDark) Color.Black else normalContainerColor
        ),
        border = if (isAmoledDark) AmoledDefaultBorder else null,
        content = content,
    )
}

/**
 * Elevated card variant for AMOLED dark mode.
 * AMOLED: pure black background + subtle border, zero shadow.
 * Normal: uses default elevated card appearance with shadow.
 */
@Composable
fun AmoledElevatedCard(
    isAmoledDark: Boolean,
    modifier: Modifier = Modifier,
    normalContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape: Shape = MaterialTheme.shapes.medium,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isAmoledDark) Color.Black else normalContainerColor
        ),
        content = content,
    )
}

/**
 * Surface wrapper that automatically adapts for AMOLED dark mode.
 * Use for non-Card composables (e.g., ToolCardScaffold, Dialog surfaces).
 *
 * Replaces the repeating pattern:
 * ```
 * Surface(
 *     color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
 *     border = if (isAmoled) BorderStroke(1.dp, ...) else null,
 *     tonalElevation = if (isAmoled) 0.dp else 6.dp,
 * )
 * ```
 */
@Composable
fun AmoledSurface(
    isAmoledDark: Boolean,
    modifier: Modifier = Modifier,
    normalColor: Color = MaterialTheme.colorScheme.surface,
    normalTonalElevation: Dp = 0.dp,
    shape: Shape = MaterialTheme.shapes.extraSmall,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = if (isAmoledDark) Color.Black else normalColor,
        border = if (isAmoledDark) AmoledDefaultBorder else null,
        tonalElevation = if (isAmoledDark) 0.dp else normalTonalElevation,
        content = content,
    )
}

/**
 * Modifier extension for applying AMOLED surface style to non-Card/Surface composables.
 * Adds border in AMOLED mode.
 */
@Composable
fun Modifier.amoledSurface(
    isAmoledDark: Boolean,
): Modifier {
    return if (isAmoledDark) {
        this.then(
            border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MEDIUM),
            )
        )
    } else {
        this
    }
}
