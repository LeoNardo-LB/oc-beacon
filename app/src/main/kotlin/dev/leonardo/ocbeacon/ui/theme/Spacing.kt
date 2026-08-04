package dev.leonardo.ocbeacon.ui.theme

/**
 * 基于 4dp 网格的语义化间距令牌。
 *
 * 用法：`Modifier.padding(SpacingTokens.LG.dp)` 或
 * `Arrangement.spacedBy(SpacingTokens.SM.dp)`。
 *
 * 令牌刻度：
 *   XS  (4)  — 最小间距：图标内边距、细线间距、精细分隔线
 *   SM  (8)  — 紧凑间距：相关元素成组间距、小内边距
 *   MD  (12) — 中等间距：卡片内边距、组件间距
 *   LG  (16) — 标准内容内边距、屏幕水平边距（最常用）
 *   XL  (24) — 区块间距、屏幕垂直边距
 *   XXL (32) — 大区块分隔
 *
 * 仅用于间距/内边距 — 不适用于组件尺寸（图标 `size`、
 * 固定 `width`/`height`）。超出此刻度的值（如 6、10、14、18、20）
 * 属组件特例，无合适语义令牌时可保持内联。
 */
object SpacingTokens {
    const val XS = 4
    const val SM = 8
    const val MD = 12
    const val LG = 16
    const val XL = 24
    const val XXL = 32
}
