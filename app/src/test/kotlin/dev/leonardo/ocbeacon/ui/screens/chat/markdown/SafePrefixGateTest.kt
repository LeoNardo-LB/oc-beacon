package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #437 SafePrefixGate 判定表（spec §5 阶段 A 用例集）。
 *
 * 断言的是「放行前缀内不含差终止符构造」——放行长度本身；
 * 渲染层语义（高度单调量子增长）由放行流单调性保证（见单调性用例）。
 */
class SafePrefixGateTest {

    private fun rel(snapshot: String, already: Int = 0): Int =
        SafePrefixGate.releaseLength(snapshot, already)

    @Test
    fun `空串快照放行0`() {
        assertEquals(0, rel(""))
    }

    @Test
    fun `已放行超过快照长度时原样返回`() {
        // 防御语义：alreadyReleased 超长时夹到快照长度（非前缀由 pilot 重建路径拦截）
        assertEquals(3, rel("abc", 5))
    }

    @Test
    fun `纯文字单行未完行全扣`() {
        // 无换行：行中间前缀有重释义风险（未来同行可能出现标记）
        assertEquals(0, rel("word1 word2"))
    }

    @Test
    fun `纯文字放行至最后换行边界`() {
        // "line1\n" 放行，末行 "line2" 扣住
        assertEquals(6, rel("line1\nline2"))
    }

    @Test
    fun `末换行等于快照末尾时扣住末行防setext`() {
        // b=lastNl+1==len → 未来可能来 "---" 升格 line2 → 退倒数第二换行
        assertEquals(6, rel("line1\nline2\n"))
    }

    @Test
    fun `单行加换行整段扣`() {
        // 唯一换行在快照末尾：末行 "Title" 有 setext 风险，无倒数第二换行可退
        assertEquals(0, rel("Title\n"))
    }

    @Test
    fun `空行毕业放行含空行`() {
        // "para one\n\n" 定案放行，新段 "next" 扣住
        assertEquals(10, rel("para one\n\nnext"))
    }

    @Test
    fun `标记回退到标记首现前`() {
        // 区="text\n"（标记 `*` 截断），lastNl+1=5 < len → 放 "text\n"
        assertEquals(5, rel("text\n*bold* more"))
    }

    @Test
    fun `标记段空行闭合后整段毕业`() {
        // "*bold* done\n\n" 闭合定案全放，"after" 未完行扣
        assertEquals(13, rel("*bold* done\n\nafter"))
    }

    @Test
    fun `行首有序列表起始扣住`() {
        // "intro\n" 放行；行首 "1." 是列表起始——渲染成列表项会重排，扣
        assertEquals(6, rel("intro\n1. first\n2. second"))
    }

    @Test
    fun `行中数字不触发有序列表规则`() {
        // "version 1.9" 的 1 非行首 → 无标记 → 单行未完 → 全扣（=0，不因数字截断）
        assertEquals(0, rel("version 1.9 is out"))
    }

    @Test
    fun `代码围栏开标记整段扣留`() {
        // 反引号是活动标记且在段首 → textLimit=0 → 全扣，等空行闭合按块成型
        assertEquals(0, rel("```kotlin\nval x = 1\n"))
    }

    @Test
    fun `围栏代码块空行后整体毕业`() {
        val s = "```kotlin\nval x = 1\n```\n\nafter"
        // 空行@围栏闭合后："```kotlin\nval x = 1\n```\n\n" 定案全放（25 字符）
        assertEquals(25, rel(s))
    }

    @Test
    fun `表格行扣留`() {
        // '|' 在标记集 → 段首截断 → 全扣
        assertEquals(0, rel("| a | b |\n|---|---|\n| 1 | 2 |"))
    }

    @Test
    fun `表格空行闭合毕业`() {
        val s = "| a | b |\n|---|---|\n\ntail"
        // 表格+空行定案（10+10+1=21 字符）放行，"tail" 扣
        assertEquals(21, rel(s))
    }

    @Test
    fun `超长纯文字段不崩溃且全扣`() {
        val s = buildString { repeat(3000) { append("word$it ") } } // 无换行
        assertEquals(0, rel(s))
    }

    @Test
    fun `多批快照放行单调不回退`() {
        var released = 0
        val batches = listOf(
            "Hello", "Hello world\nthis is", "Hello world\nthis is a **te",
            "Hello world\nthis is a **test** of\n", "Hello world\nthis is a **test** of\nstreaming.\n\nDone.",
        )
        val releases = batches.map { snap ->
            val r = rel(snap, released)
            assert(r >= released) { "放行回退: $released -> $r @ '$snap'" }
            released = r
            r
        }
        // 第1批单行扣0；第2批放首行；第3批标记前截断仍7；第4批**test**行仍扣（标记行）；
        // 第5批空行毕业跳到定案整段
        // 第5批空行毕业：放行 "**test**" 行 + "streaming.\n\n"（12+22+11+1=46）
        assertEquals(listOf(0, 12, 12, 12, 46), releases)
    }

    @Test
    fun `already非零时从边界继续放行`() {
        // 上批放行到 10（"para one\n\n"）；本批增量 "para one\n\nnext line\nmore"
        assertEquals(20, rel("para one\n\nnext line\nmore", 10))
    }

    @Test
    fun `表格粘边注入补空行`() {
        val snap = "para\n| a |\n|---|\n| 1 |\n\ntail"
        val d = SafePrefixGate.releaseDelta(snap, 0)
        assertEquals("para\n\n| a |\n|---|\n| 1 |\n\n", d.delta) // para 与表头间注入空行
        assertEquals(24, d.newReleased) // 空行毕业到 tail 前
    }

    @Test
    fun `表格已有空行不注入`() {
        val snap = "para\n\n| a |\n|---|\n\ntail"
        val d = SafePrefixGate.releaseDelta(snap, 0)
        assertEquals("para\n\n| a |\n|---|\n\n", d.delta) // 已有空行，零注入
        assertEquals(19, d.newReleased)
    }

    @Test
    fun `表格前行为表格延续不注入`() {
        val snap = "| a |\n|---|\n| 1 |\n\ntail"
        val d = SafePrefixGate.releaseDelta(snap, 0)
        assertEquals("| a |\n|---|\n| 1 |\n\n", d.delta) // 快照首即表格，无前行不注入
        assertEquals(19, d.newReleased)
    }

    @Test
    fun `tasklist字符扣留`() {
        val snap = "- ☐ task one\n"
        // 扣留（tasklist/math 完结变换字符触发差终止符）
        assertEquals(0, SafePrefixGate.releaseLength(snap, 0))
    }

    @Test
    fun `数学块双美元扣留`() {
        val snap = "formula \$\$x^2\$\$ next"
        // 扣留（tasklist/math 完结变换字符触发差终止符）
        assertEquals(0, SafePrefixGate.releaseLength(snap, 0))
    }

    @Test
    fun `单美元放行不扣`() {
        val snap = "price is 5$\nnext line"
        val d = SafePrefixGate.releaseDelta(snap, 0)
        assertEquals("price is 5$\n", d.delta)
        assertEquals(12, d.newReleased) // 换行边界，扣住未完行 next line
    }

    @Test
    fun `纯文字扣留尾判定无标记`() {
        assertFalse(SafePrefixGate.heldTailHasActiveMarkers(""))
        assertFalse(SafePrefixGate.heldTailHasActiveMarkers("The keeper climbed the winding stairs slowly."))
        assertFalse(SafePrefixGate.heldTailHasActiveMarkers("line one\nline two continues"))
    }

    @Test
    fun `含标记扣留尾判定有标记`() {
        assertTrue(SafePrefixGate.heldTailHasActiveMarkers("bold *text* here"))
        assertTrue(SafePrefixGate.heldTailHasActiveMarkers("\u2610 task"))
        assertTrue(SafePrefixGate.heldTailHasActiveMarkers("math \$\$x\$\$"))
        assertTrue(SafePrefixGate.heldTailHasActiveMarkers("intro\n1. first"))  // 行首有序列表
        assertFalse(SafePrefixGate.heldTailHasActiveMarkers("price 5 each"))  // 单 $ 非标记
    }

    @Test
    fun `CRLF空行毕业兼容`() {
        // CR 视作行内空白：空行检测跳过 \r
        assertEquals(12, rel("para one\r\n\r\nafter"))
    }
}
