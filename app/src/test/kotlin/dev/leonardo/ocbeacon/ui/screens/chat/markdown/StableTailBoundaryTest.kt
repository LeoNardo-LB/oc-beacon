package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R2 双容器（稳定前缀 state + 活跃尾 state）的边界纯函数。
 *
 * 机制：流式 markdown 渲染拆两个子树——稳定前缀子树内容只在块毕业（空行）时
 * 低频 append（Compose measure 缓存命中，零重排）；活跃尾子树承载增量 append
 * （每次只重排尾块）——append 成本 O(总内容)→O(尾块)。
 */
class StableTailBoundaryTest {

    @Test
    fun `空行块末尾为稳定边界`() {
        // "abc\n\ndef" released=8 → 最后空行块末尾=5（"abc\n\n" 整体为稳定前缀）
        assertEquals(5, stableTailBoundary("abc\n\ndef", 8))
    }

    @Test
    fun `无空行全为尾`() {
        assertEquals(0, stableTailBoundary("abc def", 7))
    }

    @Test
    fun `多个空行块取最后`() {
        // "a\n\nb\n\nc" → 最后空行块末尾=6
        assertEquals(6, stableTailBoundary("a\n\nb\n\nc", 9))
    }

    @Test
    fun `尾部恰空行全稳定`() {
        assertEquals(5, stableTailBoundary("abc\n\n", 5))
    }

    @Test
    fun `连续多空行跳到末尾`() {
        // 连续空行是一块——块末尾=最后一个空行后=6
        assertEquals(6, stableTailBoundary("ab\n\n\n\nc", 9))
    }

    @Test
    fun `released为零边界零`() {
        assertEquals(0, stableTailBoundary("anything", 0))
    }
}
