package dev.leonardo.ocbeacon.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 集中式按钮样式令牌。
 *
 * 用法：
 * ```kotlin
 * // 主要（填充按钮）
 * Button(colors = ButtonTokens.filledColors(), border = ButtonTokens.amoledBorder())
 *
 * // 次要（OutlinedButton — 无需自定义颜色）
 * OutlinedButton() // 使用 Material 3 默认值
 *
 * // 危险（使用 error 色的填充按钮）
 * Button(colors = ButtonTokens.dangerColors(), border = ButtonTokens.amoledBorder())
 * ```
 */
object ButtonTokens {

    // ── 内容内边距 ──────────────────────────────────────────────

    /** 紧凑垂直内边距，用于全宽堆叠按钮（Column 中 3 个及以上）。 */
    val CompactPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)

    /** Column 中全宽堆叠按钮之间的间距。 */
    const val StackSpacing = 4

    /** Row 中内联按钮之间的间距。 */
    const val RowSpacing = 8

    // ── 填充按钮颜色（主要） ────────────────────────────────

    /**
     * 用于主要操作 [Button]（填充）的颜色。
     *
     * - **浅色 / 深色**：Material 3 默认值（`primary`/`onPrimary`）。
     * - **AMOLED**：黑色容器 + primary 内容色。
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

    // ── 危险填充按钮颜色 ───────────────────────────────────

    /**
     * 用于危险 [Button]（删除 / 破坏性操作）的颜色。
     *
     * - **浅色 / 深色**：`error` / `onError`（Material 3 error）。
     * - **AMOLED**：黑色容器 + error 内容色。
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

    // ── AMOLED 边框 ────────────────────────────────────────────

    /**
     * 适配当前主题的按钮边框。
     *
     * - **AMOLED**：1dp primary 边框，使用 [AlphaTokens.HIGH] 透明度。
     * - **浅色 / 深色**：`null`（无边框）。
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
