package dev.leonardo.ocbeacon.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * 用于 [androidx.compose.material3.ListItem] 内容的语义化内边距令牌。
 * 三档密度级别，匹配项目的令牌系统模式。
 */
object ListItemTokens {
    /** 紧凑 — 最小垂直内边距。 */
    val ContentPaddingSmall = PaddingValues(horizontal = 16.dp, vertical = 2.dp)
    /** 中等 — 平衡密度（设置类列表的默认值）。 */
    val ContentPaddingMedium = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
    /** 大 — Material 3 默认密度。 */
    val ContentPaddingLarge = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
}
