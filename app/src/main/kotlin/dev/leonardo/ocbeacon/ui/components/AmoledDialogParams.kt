package dev.leonardo.ocbeacon.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Shape
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.LocalAmoledMode
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens

/**
 * 用于对话框 Surface 的参数，会自适应 AMOLED 模式。
 *
 * AMOLED：Color.Black + 0dp 高度 + 1dp outlineVariant/HIGH 边框。
 * 普通：指定颜色 + 指定高度 + 无边框。
 */
data class AmoledDialogParams(
    val containerColor: Color,
    val tonalElevation: Dp,
    val border: BorderStroke?,
    val shape: Shape,
)

/**
 * 创建能自动适配当前 AMOLED 主题状态的 [AmoledDialogParams]。
 *
 * @param normalColor      非 AMOLED 模式下的 Surface 颜色。默认：surfaceContainerHigh。
 * @param normalElevation  非 AMOLED 模式下的色调高度。默认：6.dp。
 * @param shape            对话框 Surface 的圆角形状。默认：ShapeTokens.extraLarge（28dp）。
 */
@Composable
fun amoledDialogParams(
    normalColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    normalElevation: Dp = 6.dp,
    shape: Shape = ShapeTokens.extraLarge,
): AmoledDialogParams {
    val isAmoled = LocalAmoledMode.current
    return if (isAmoled) {
        AmoledDialogParams(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.HIGH)
            ),
            shape = shape,
        )
    } else {
        AmoledDialogParams(
            containerColor = normalColor,
            tonalElevation = normalElevation,
            border = null,
            shape = shape,
        )
    }
}

/**
 * AMOLED 模式下的 [TextFieldColors] — 纯黑容器。
 * 在 `if (isAmoled)` 分支内使用，避免在每个调用点重复写 Color.Black。
 */
@Composable
fun amoledOutlinedTextFieldColors(): TextFieldColors {
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        disabledContainerColor = MaterialTheme.colorScheme.surface,
    )
}
