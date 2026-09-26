package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * markdown 稳态粒度扩展（用户裁决 P3）：引用块/ATX 标题/嵌套列表的行级放行。
 */
class SafePrefixGateBlockGranularityTest {

    private fun rel(snapshot: String, already: Int = 0): Int =
        SafePrefixGate.releaseLength(snapshot, already)

    @Test
    fun `引用块行逐行放行`() {
        val s = "> 第一段引用\n> 第二段引用\n"
        assertEquals(s.length, rel(s))
    }

    @Test
    fun `引用块带未完尾行只放完整行`() {
        // 未完行（无\n）扣留
        val got = rel("> a\n> 未完")
        assertEquals("> a\n".length, got)
    }

    @Test
    fun `ATX标题行放行`() {
        assertEquals("## 标题\n".length, rel("## 标题\n"))
        assertEquals("# T\n".length, rel("# T\n"))
    }

    @Test
    fun `标题未完行扣留`() {
        assertEquals(0, rel("# 未完标题"))
    }

    @Test
    fun `嵌套列表项行放行`() {
        assertEquals("- a\n  - b\n".length, rel("- a\n  - b\n"))
        assertEquals("* a\n  * b\n".length, rel("* a\n  * b\n"))
    }

    @Test
    fun `四级缩进代码块保持扣留`() {
        // 4+ 空格缩进=缩进代码块——不能当嵌套列表放（半行语义不同）
        assertEquals(0, rel("    code line\n"))
    }
}
