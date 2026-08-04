package dev.leonardo.ocbeacon.ui.theme

/**
 * 语义化透明度令牌，保证应用内内容强调的一致性。
 *
 * 用法：`colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)`
 *
 * 令牌刻度：
 *   SELECTED (0.12) — 选中/高亮背景、chips、@提及高亮
 *   DIFF_BG  (0.10) — diff/代码块背景填充
 *   FAINT    (0.35) — 元信息、时间戳、占位符、细微分隔线
 *   MUTED    (0.50) — 次要文本、细微图标、次要边框
 *   MEDIUM   (0.70) — 第三级内容、标准边框、主要文本变体
 *   HIGH     (0.80) — 控件边框、选中指示器、卡片边框
 *   AMOLED   (0.92) — AMOLED 模式代码文本最大对比度
 */
object AlphaTokens {
    const val SELECTED = 0.12f
    const val DIFF_BG = 0.1f
    const val FAINT = 0.35f
    const val MUTED = 0.50f
    const val MEDIUM = 0.70f
    const val HIGH = 0.80f
    const val AMOLED = 0.92f
}
