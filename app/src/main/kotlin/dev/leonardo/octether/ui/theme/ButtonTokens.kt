package dev.leonardo.octether.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Centralized button style tokens.
 *
 * Usage:
 * ```kotlin
 * // Primary (Filled Button)
 * Button(colors = ButtonTokens.filledColors(), border = ButtonTokens.amoledBorder())
 *
 * // Secondary (OutlinedButton — no custom colors needed)
 * OutlinedButton() // use Material 3 defaults
 *
 * // Danger (Filled Button with error color)
 * Button(colors = ButtonTokens.dangerColors(), border = ButtonTokens.amoledBorder())
 * ```
 */
object ButtonTokens {

    // ── Content Padding ──────────────────────────────────────────────

    /** Compact vertical padding for full-width stacked buttons (3+ in a Column). */
    val CompactPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)

    /** Spacing between stacked full-width buttons in a Column. */
    const val StackSpacing = 4

    /** Spacing between inline buttons in a Row. */
    const val RowSpacing = 8

    // ── Filled Button Colors (Primary) ────────────────────────────────

    /**
     * Colors for [Button] (Filled) for Primary actions.
     *
     * - **Light / Dark**: Material 3 default (`primary`/`onPrimary`).
     * - **AMOLED**: Black container + primary content.
     */
    @Composable
    fun filledColors(): ButtonColors {
        val isAmoled = LocalAmoledMode.current
        return if (isAmoled) {
            ButtonDefaults.buttonColors(
                containerColor = Color.Black,
                contentColor = MaterialTheme.colorScheme.primary,
            )
        } else {
            ButtonDefaults.buttonColors()
        }
    }

    // ── Danger Filled Button Colors ───────────────────────────────────

    /**
     * Colors for danger [Button] (delete / destructive).
     *
     * - **Light / Dark**: `error` / `onError` (Material 3 error).
     * - **AMOLED**: Black container + error content.
     */
    @Composable
    fun dangerColors(): ButtonColors {
        val isAmoled = LocalAmoledMode.current
        return if (isAmoled) {
            ButtonDefaults.buttonColors(
                containerColor = Color.Black,
                contentColor = MaterialTheme.colorScheme.error,
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        }
    }

    // ── AMOLED Border ────────────────────────────────────────────────

    /**
     * Border for buttons adapted to the current theme.
     *
     * - **AMOLED**: 1dp primary border with [AlphaTokens.HIGH] alpha.
     * - **Light / Dark**: `null` (no border).
     */
    @Composable
    fun amoledBorder(): BorderStroke? {
        val isAmoled = LocalAmoledMode.current
        return if (isAmoled) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.HIGH))
        } else {
            null
        }
    }
}
