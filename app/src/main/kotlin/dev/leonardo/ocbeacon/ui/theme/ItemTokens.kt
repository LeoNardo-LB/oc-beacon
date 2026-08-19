package dev.leonardo.ocbeacon.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 列表项高度/密度规格令牌（#81：度量统一提取 token 主题系统）。
 *
 * 与 [ListItemTokens]（内容内边距）互补——本对象只管行高规格。
 * 用法：`Modifier.heightIn(min = ItemTokens.MinHeightDense.dp)`。
 *
 * 刻度（对齐 Material 3 列表密度惯例）：
 *   Dense    (40) — 高密度选择列表：模型/agent 选择器（一屏尽量多行，
 *                    单行文本 + 尾随图标/标签）
 *   Compact  (48) — 次级列表、设置项：M3 ListItem 默认紧凑档
 *   Standard (56) — 主列表、导航目标：单手易触达的标准触摸行高
 */
object ItemTokens {
    /** 密集 — 高密度选择列表行最小高度。 */
    const val MinHeightDense = 40
    /** 紧凑 — 次级列表/设置项行最小高度。 */
    const val MinHeightCompact = 48
    /** 标准 — 主列表/导航目标行最小高度。 */
    const val MinHeightStandard = 56
}
