package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #437 SafePrefixGate 判定表（2026-09-26 增量直出语义重钉）。
 *
 * 纯文字（无活动标记）未完行逐批增量直出（字面=最终）；含标记行/围栏/表格
 * 按闭合语义；单批预算 400 循环内扣减。
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
        assertEquals(3, rel("abc", 5))
    }

    @Test
    fun `纯文字未完行增量直出`() {
        assertEquals(11, rel("word1 word2"))
    }

    @Test
    fun `纯文字多行含未完行全直出`() {
        assertEquals(11, rel("line1\nline2"))
    }

    @Test
    fun `末换行快照全量直出`() {
        assertEquals(12, rel("line1\nline2\n"))
    }

    @Test
    fun `单行加换行整段直出`() {
        assertEquals(6, rel("Title\n"))
    }

    @Test
    fun `空行毕业放行含空行`() {
        assertEquals(14, rel("para one\n\nnext"))
    }

    @Test
    fun `标记回退到标记首现前`() {
        assertEquals(5, rel("text\n*bold* more"))
    }

    @Test
    fun `标记段空行闭合后整段毕业加尾行直出`() {
        assertEquals(18, rel("*bold* done\n\nafter"))
    }

    @Test
    fun `行首有序列表起始扣住`() {
        assertEquals(6, rel("intro\n1. first\n2. second"))
    }

    @Test
    fun `行中数字不触发有序列表规则且直出`() {
        assertEquals(18, rel("version 1.9 is out"))
    }

    @Test
    fun `代码围栏开标记整段扣留`() {
        assertEquals(0, rel("```kotlin\nval x = 1\n"))
    }

    @Test
    fun `围栏代码块空行后整体毕业`() {
        val s = "```kotlin\nval x = 1\n```\n\nafter"
        assertEquals(30, rel(s))
    }

    @Test
    fun `表格完整行逐行放行`() {
        assertEquals(20, rel("| a | b |\n|---|---|\n| 1 | 2 |"))
    }

    @Test
    fun `表格未完行扣住`() {
        assertEquals(20, rel("| a | b |\n|---|---|\n| 1 | 2"))
    }

    @Test
    fun `表格完整行逐行渐显含尾换行`() {
        assertEquals(40, rel("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n"))
    }

    @Test
    fun `表头分隔未齐整块扣`() {
        assertEquals(0, rel("| a | b |\ntext"))
    }

    @Test
    fun `未闭合围栏整块扣留含内容行`() {
        assertEquals(5, rel("text\n```kotlin\nval x = 1\nval y = 2"))
        assertEquals(39, rel("text\n```kotlin\nval x = 1\nval y = 2\n```\n"))
    }

    @Test
    fun `超长纯文字段按预算直出`() {
        val s = buildString { repeat(3000) { append("word") ; append(it) ; append(" ") } }
        assertEquals(400, rel(s))
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
            assert(r >= released)
            released = r
            r
        }
        assertEquals(listOf(5, 19, 19, 19, 51), releases)
    }

    @Test
    fun `already非零时从边界继续放行`() {
        assertEquals(24, rel("para one\n\nnext line\nmore", 10))
    }

    @Test
    fun `表格粘边注入补空行`() {
        val snap = "para\n| a |\n|---|\n| 1 |\n\ntail"
        val d = SafePrefixGate.releaseDelta(snap, 0)
        assertEquals("para\n\n| a |\n|---|\n| 1 |\n\ntail", d.delta)
        assertEquals(28, d.newReleased)
    }

    @Test
    fun `表格已有空行不注入`() {
        val snap = "para\n\n| a |\n|---|\n\ntail"
        val d = SafePrefixGate.releaseDelta(snap, 0)
        assertEquals("para\n\n| a |\n|---|\n\ntail", d.delta)
        assertEquals(23, d.newReleased)
    }

    @Test
    fun `表格前行为表格延续不注入`() {
        val snap = "| a |\n|---|\n| 1 |\n\ntail"
        val d = SafePrefixGate.releaseDelta(snap, 0)
        assertEquals("| a |\n|---|\n| 1 |\n\ntail", d.delta)
        assertEquals(23, d.newReleased)
    }

    @Test
    fun `tasklist字符扣留`() {
        assertEquals(0, SafePrefixGate.releaseLength("- ☐ task one\n", 0))
    }

    @Test
    fun `数学块双美元扣留`() {
        assertEquals(0, SafePrefixGate.releaseLength("formula \$\$x^2\$\$ next", 0))
    }

    @Test
    fun `单美元放行不扣`() {
        val snap = "price is 5$\nnext line"
        val d = SafePrefixGate.releaseDelta(snap, 0)
        assertEquals("price is 5$\nnext line", d.delta)
        assertEquals(21, d.newReleased)
    }

    @Test
    fun `CRLF空行毕业兼容`() {
        assertEquals(17, rel("para one\r\n\r\nafter"))
    }

    @Test
    fun `预算中点截断续放一致`() {
        val s = "a".repeat(1000)
        assertEquals(400, rel(s, 0))
        assertEquals(800, rel(s, 400))
        assertEquals(1000, rel(s, 800))
    }

    @Test
    fun `行续段不作表头判定`() {
        assertEquals(3, rel("abc | a |\n|---|\n| 1 |\n", 3))
    }

    @Test
    fun `行续段纯文字继续直出`() {
        assertEquals(7, rel("abc def", 3))
    }
}
