package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R2 侦察成果（贴底 framestats 定罪）：呼吸光标原以 Text inline content 实现——
 * 动画值每帧变化被放大为整段 Text 重排+重绘（trav 13ms/draw 3-10ms 每帧）。
 * 根修：光标移出 Text，独立叠加层——[cursorOffsetFromLayout] 从文本布局结果
 * 计算光标叠加位置（纯函数缝）。
 */
class HeldTailCursorTest {

    @Test
    fun `末行右端之后放置光标`() {
        // 末行: 宽 300、行底 y=88（基线上方）——光标 x=末端+间距, y=末行基线
        val off = cursorOffsetFromLayout(
            lastLineRight = 300f,
            lastLineBaseline = 88f,
            cursorWidth = 10f,
            cursorHeight = 20f,
            cursorGap = 4f,
        )
        assertEquals(300f + 4f, off.x, 0.01f)
        // y 语义=光标 top（基线对齐光标底部）→ baseline - cursorHeight
        assertEquals(88f - 20f, off.y, 0.01f)
    }

    @Test
    fun `行末溢出时回绕留白不越界`() {
        val off = cursorOffsetFromLayout(
            lastLineRight = 998f,
            lastLineBaseline = 88f,
            cursorWidth = 10f,
            cursorHeight = 20f,
            cursorGap = 4f,
            maxWidth = 1000f,
        )
        // 末端+光标超宽 → 放在 maxWidth 处（行内尾部，clip 裁掉多余）
        assertEquals(1000f - 10f, off.x, 0.01f)
    }

    @Test
    fun `基线对齐光标底部`() {
        val off = cursorOffsetFromLayout(
            lastLineRight = 100f,
            lastLineBaseline = 50f,
            cursorWidth = 8f,
            cursorHeight = 16f,
            cursorGap = 2f,
        )
        // y 是光标 top：基线对齐光标底 → top = baseline - cursorHeight
        assertEquals(50f - 16f, off.y, 0.01f)
    }
}
