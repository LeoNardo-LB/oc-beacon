package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-08-18 E2E-E 回归：QuestionPagerView 页高插值 + 上限截断纯函数。
 *
 * 背景：6+ 选项页（含自定义输入）内容高于视口时，卡片在消息流中整体不可达
 * （reverseLayout 锚定 + 自动回底，任何手势滚不进视口）——修复为页内容限高
 * （屏高 40%）+ 页内滚动；插值对截断后的高度进行，卡片高度恒定于上限。
 */
class QuestionPagerHeightTest {

    @Test
    fun `both pages below cap interpolate linearly`() {
        // 原始插值语义保持：低页 300 → 高页 500，进度 0.5 → 400
        assertEquals(400, lerpCappedPageHeight(300, 500, 0.5f, 1000))
    }

    @Test
    fun `tall pages clamp to cap`() {
        // 两页均超上限（1200/1500 > 1000）→ 插值恒为上限，不撑爆卡片
        assertEquals(1000, lerpCappedPageHeight(1200, 1500, 0f, 1000))
        assertEquals(1000, lerpCappedPageHeight(1200, 1500, 0.5f, 1000))
        assertEquals(1000, lerpCappedPageHeight(1200, 1500, 1f, 1000))
    }

    @Test
    fun `mixed pages interpolate between capped values`() {
        // 短页 300 → 高页 1200（截为 1000）：进度 0.5 → (300+1000)/2 = 650
        assertEquals(650, lerpCappedPageHeight(300, 1200, 0.5f, 1000))
    }

    @Test
    fun `unmeasured from page returns zero for wrap gate`() {
        // fromHeight=0（未测量）→ 0：调用方保持 wrap（高度 0 会塌陷）
        assertEquals(0, lerpCappedPageHeight(0, 800, 0.5f, 1000))
    }

    @Test
    fun `progress clamped to unit range`() {
        // 越界进度防御：progress=2 等价 1（完整目标高度）
        assertEquals(500, lerpCappedPageHeight(300, 500, 2f, 1000))
        assertEquals(300, lerpCappedPageHeight(300, 500, -1f, 1000))
    }
}
