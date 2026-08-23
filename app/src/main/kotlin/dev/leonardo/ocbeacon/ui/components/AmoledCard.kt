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
 * AMOLED 模式下使用的标准边框：1dp outlineVariant，不透明度 [AlphaTokens].MEDIUM = 0.70。
 */
internal val AmoledDefaultBorder: BorderStroke
    @Composable get() = BorderStroke(
        1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MEDIUM)
    )

/**
 * 自动适配 AMOLED 深色模式的 Card。
 * AMOLED：纯黑背景 + 细微边框，无高度。
 * 普通：使用 [normalContainerColor]，无边框。
 *
 * 替代重复模式：
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
 * AMOLED 深色模式下的 ElevatedCard 变体。
 * AMOLED：纯黑背景 + 细微边框，无阴影。
 * 普通：使用默认的 elevated card 外观，带阴影。
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
 * 自动适配 AMOLED 深色模式的 Surface 包装。
 * 用于非 Card 的 composable（如 ToolCardScaffold、对话框 Surface）。
 *
 * 替代重复模式：
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
 * Modifier 扩展，将 AMOLED Surface 样式应用到非 Card/Surface 的 composable。
 * 在 AMOLED 模式下添加边框。
 */
@Composable
fun Modifier.amoledSurface(
    isAmoledDark: Boolean,
): Modifier {
    return if (isAmoledDark) {
        // #106 lint 清偿：原 this.then(border(...)) 中 border 以隐式接收者链在 this 上，
        // then() 纯冗余——直接链式等价简化
        this.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MEDIUM),
        )
    } else {
        this
    }
}
