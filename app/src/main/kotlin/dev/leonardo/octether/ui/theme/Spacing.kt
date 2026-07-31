package dev.leonardo.octether.ui.theme

/**
 * Semantic spacing tokens based on a 4dp grid.
 *
 * Usage: `Modifier.padding(SpacingTokens.LG.dp)` or
 * `Arrangement.spacedBy(SpacingTokens.SM.dp)`.
 *
 * Token scale:
 *   XS  (4)  — minimal gaps: icon insets, hairline spacing, fine separators
 *   SM  (8)  — tight gaps: related-element group spacing, small padding
 *   MD  (12) — medium gaps: card inner padding, component spacing
 *   LG  (16) — standard content padding, screen horizontal margin (most common)
 *   XL  (24) — section spacing, screen vertical margin
 *   XXL (32) — large section separation
 *
 * Apply to spacing/padding only — not to component sizing (icon `size`,
 * fixed `width`/`height`). Values outside this scale (e.g. 6, 10, 14, 18, 20)
 * are component-specific and may remain inline where no semantic token fits.
 */
object SpacingTokens {
    const val XS = 4
    const val SM = 8
    const val MD = 12
    const val LG = 16
    const val XL = 24
    const val XXL = 32
}
