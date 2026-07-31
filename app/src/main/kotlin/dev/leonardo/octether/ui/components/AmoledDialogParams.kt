package dev.leonardo.octether.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Shape
import dev.leonardo.octether.ui.theme.AlphaTokens
import dev.leonardo.octether.ui.theme.LocalAmoledMode
import dev.leonardo.octether.ui.theme.ShapeTokens

/**
 * Parameters for a dialog Surface that adapts to AMOLED mode.
 *
 * AMOLED: Color.Black + 0dp elevation + 1dp outlineVariant/HIGH border.
 * Normal: specified color + specified elevation + no border.
 */
data class AmoledDialogParams(
    val containerColor: Color,
    val tonalElevation: Dp,
    val border: BorderStroke?,
    val shape: Shape,
)

/**
 * Creates [AmoledDialogParams] that automatically adapt to the current AMOLED theme state.
 *
 * @param normalColor      Surface color in non-AMOLED mode. Default: surfaceContainerHigh.
 * @param normalElevation  Tonal elevation in non-AMOLED mode. Default: 6.dp.
 * @param shape            Corner shape for the dialog surface. Default: ShapeTokens.extraLarge (28dp).
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
 * [TextFieldColors] for AMOLED mode — pure black container.
 * Use inside `if (isAmoled)` branches to eliminate per-site Color.Black repetition.
 */
@Composable
fun amoledOutlinedTextFieldColors(): TextFieldColors {
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        disabledContainerColor = MaterialTheme.colorScheme.surface,
    )
}
