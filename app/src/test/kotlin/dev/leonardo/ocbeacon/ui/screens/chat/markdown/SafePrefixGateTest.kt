package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `标记行闭合构造整行放行`() {
        // #472：*bold* 闭合即定案——文字直出到快照尾（原整行扣到空行毕业）
        assertEquals(16, rel("text\n*bold* more"))
    }

    @Test
    fun `标记段空行闭合后整段毕业加尾行直出`() {
        assertEquals(18, rel("*bold* done\n\nafter"))
    }

    @Test
    fun `有序列表项逐行放行_裁决441修订`() {
        // #441 用户裁决修订：有序列表项行级定案逐行放行（原整块扣留语义废止）
        // #472：未完尾行 "2. second" 无行内构造，扫描器直出 = 全量
        assertEquals(24, rel("intro\n1. first\n2. second"))
    }

    @Test
    fun `行中数字不触发有序列表规则且直出`() {
        assertEquals(18, rel("version 1.9 is out"))
    }

    @Test
    fun `代码围栏开行完整即放_487裁决B`() {
        // #487：开放围栏行级放行——开行完整即放（渲染器按 CommonMark 将未闭合
        // 围栏延伸至前缀尾渲染为增长代码块，探针实证），内部完整行整放。
        assertEquals(20, rel("```kotlin\nval x = 1\n"))
    }

    @Test
    fun `开放围栏未完尾行增量直出`() {
        // 未完行（无换行）按纯文字增量放——代码字面=最终，真 token 级
        val s = "text\n```kotlin\nval x = 1\nval y = 2"
        assertEquals(s.length, rel(s))
    }

    @Test
    fun `未完开行扣留等完整`() {
        // 半行闭栏误判块态 + 语言标注未定：未完开行不进围栏语义
        assertEquals(0, rel("```kot"))
        assertEquals(5, rel("text\n``"))
    }

    @Test
    fun `闭栏形完整行即放即闭_后续正文回普通语义`() {
        assertEquals(34, rel("```kotlin\nval x = 1\n```\nplain tail"))
    }

    @Test
    fun `闭栏后未闭合行内构造仍扣留`() {
        val s = "```\ncode\n```\n**uncl"
        assertTrue(rel(s) < s.length)
    }

    @Test
    fun `四反引号围栏内三反引号行不闭栏_预算续放`() {
        // 预算截断后第二批续放：fenceOpenAt 恢复开栏 (`,4)，内部 ``` 行按
        // 开栏长度感知判定为内容（长度盲判定会把 ``**tail 当栏外行内构造扣留）
        val s = "````\ninner\n```\n**tail"
        val first = SafePrefixGate.releaseLength(s, 0, 8)
        assertTrue(first in 1 until s.length)
        val second = SafePrefixGate.releaseLength(s, first)
        assertEquals(s.length, second)
    }

    @Test
    fun `波浪线围栏同语义`() {
        val s = "~~~\ncode here\n"
        assertEquals(s.length, rel(s))
    }

    @Test
    fun `列表内围栏行级放行`() {
        val s = "- item\n  ```\n  code line"
        assertEquals(s.length, rel(s))
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
    fun `未闭合围栏行级放行含内容行_487`() {
        // 旧「整块扣留」语义废止：未闭合围栏开行+内部行即时放行；
        // 闭合后同样全量（两态收敛为同一路径）
        assertEquals(34, rel("text\n```kotlin\nval x = 1\nval y = 2"))
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
        // #472：**te 悬空扣在 ** 前（19→22 放出 "this is a "）；**test** 闭合
        // 即放行整行（22→34）；完结空行毕业收尾（51）
        assertEquals(listOf(5, 19, 22, 34, 51), releases)
    }

    @Test
    fun `already非零时从边界继续放行`() {
        assertEquals(24, rel("para one\n\nnext line\nmore", 10))
    }

    @Test
    fun `表格粘边 delta 原文无注入`() {
        // #471③：注入退役——归一化前移后空行插入发生在 pilot ingest（gate
        // 入口前），releaseDelta 的 delta = 快照区间原文（坐标耦合）。
        val snap = "para\n| a |\n|---|\n| 1 |\n\ntail"
        val d = SafePrefixGate.releaseDelta(snap, 0)
        assertEquals("para\n| a |\n|---|\n| 1 |\n\ntail", d.delta)
        assertEquals(28, d.newReleased)
    }

    @Test
    fun `表格已有空行 delta 原文`() {
        val snap = "para\n\n| a |\n|---|\n\ntail"
        val d = SafePrefixGate.releaseDelta(snap, 0)
        assertEquals("para\n\n| a |\n|---|\n\ntail", d.delta)
        assertEquals(23, d.newReleased)
    }

    @Test
    fun `表格前行为表格延续 delta 原文`() {
        val snap = "| a |\n|---|\n| 1 |\n\ntail"
        val d = SafePrefixGate.releaseDelta(snap, 0)
        assertEquals("| a |\n|---|\n| 1 |\n\ntail", d.delta)
        assertEquals(23, d.newReleased)
    }

    @Test
    fun `tasklist字符起扣留前缀放行`() {
        // #472：☐ 前的 "- " 列表标记放行（空列表项先现），☐ 起扣留等空行
        // 毕业——完结归一化变换面不提前揭示
        assertEquals(2, SafePrefixGate.releaseLength("- ☐ task one\n", 0))
    }

    @Test
    fun `数学块双美元起扣留前缀放行`() {
        // #472：$$ 前纯文字放行，$$ 起扣留（完结数学变换面）
        assertEquals(8, SafePrefixGate.releaseLength("formula \$\$x^2\$\$ next", 0))
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
        // #472：竖线前纯文字 "c " 直出（管道后扣留）；管道到达前的纯文字
        // 段落→表头闪烁为预存在类（管道未到不可知），不因本修复扩大
        assertEquals(4, rel("abc | a |\n|---|\n| 1 |\n", 3))
    }

    @Test
    fun `行续段纯文字继续直出`() {
        assertEquals(7, rel("abc def", 3))
    }

    // ============ #472：行内闭合构造即时放行（InlineSpanSafety 接线） ============

    @Test
    fun `标记行行中截断后续行继续放行`() {
        // 首行闭合构造全放；次行 "d " 放出、悬空 * 扣住
        assertEquals(10, rel("a *b* c\nd *e"))
    }

    @Test
    fun `完整引用行含闭合构造放行`() {
        assertEquals(8, rel("> *a* b\n"))
    }

    @Test
    fun `完整引用行未闭合构造帽住`() {
        // '>' 内容前缀起扫：引用标记+空格+a+空格放出，悬空 * 扣住
        assertEquals(4, rel("> a *unclosed\n"))
    }

    // ===== #441 markdown 稳态粒度：表格正文跨批逐行 / * 无序列表项逐行 =====

    @Test
    fun `表格正文行跨批仍逐行放行`() {
        // 场景：表头+分隔批已放（already= 表头+分隔长度），快照续有两条正文行
        val header = "| 名称 | 数量 |\n| --- | --- |\n"
        val body = "| 苹果 | 3 |\n| 香蕉 | 5 |\n"
        val already = header.length
        // 期望：放行第一条完整正文行（第二条同批预算内也放——断言 >= 首行）
        val got = rel(header + body, already)
        assertTrue("应至少放行第一条正文行, got=" + got, got >= already + "| 苹果 | 3 |\n".length)
    }

    @Test
    fun `表格正文行单行逐放不半行`() {
        val header = "| a | b |\n| --- | --- |\n"
        val already = header.length
        // 只有半行正文（无换行）：不放
        assertEquals(already, rel(header + "| 半行", already))
    }

    @Test
    fun `星号无序列表项完整行放行`() {
        // '* ' 后跟内容 = 无序列表项（列表语义行级定案）
        val s = "* 第一项\n* 第二项\n"
        assertEquals(s.length, rel(s))
    }

    @Test
    fun `星号列表未完行流式放行`() {
        // #472：'* ' 标记星非侧翼=字面定案，未完行按行内安全扫描直出——
        // 与 '- item' 纯文字路径粒度对齐（原整行扣到行完整）
        assertEquals(6, rel("* 未完成项"))
    }

    @Test
    fun `星号强调跨行闭合放行`() {
        // #472：软换行同段——* 开、more* 闭，配对即放行（原扣到空行毕业）
        assertEquals(15, rel("*bold 开头\nmore*\n"))
    }

    @Test
    fun `横杠与有序列表项回归保持逐行`() {
        assertEquals("- item a\n".length, rel("- item a\n"))
        assertEquals("1. 第一\n".length, rel("1. 第一\n"))
    }

    // ============ #438①（2026-09-27）：单次放行上限（含空行毕业段） ============

    @Test
    fun `maxReleaseChars 截断空行毕业大段`() {
        // 三段空行毕业的定案内容（每段 60+ 字）——原第一级不受批预算约束整段放行；
        // maxReleaseChars=100 时只放 100（截断点在行中——released 坐标续放安全）。
        val seg = "这是一段足够长的定案文字用来验证单次放行上限对空行毕业段的截断行为覆盖到索引位置。"
        val snap = (seg + "\n\n").repeat(5) + seg
        assertEquals(100, SafePrefixGate.releaseLength(snap, 0, maxReleaseChars = 100))
        // 续放：从 100 继续到下一个 100
        assertEquals(200, SafePrefixGate.releaseLength(snap, 100, maxReleaseChars = 100))
        // 尾批放满
        val full = SafePrefixGate.releaseLength(snap, 0)
        assertEquals(full, SafePrefixGate.releaseLength(snap, 200, maxReleaseChars = 100))
        // 默认参数 = 原行为（整段毕业）
        assertTrue(full > 200)
    }

    @Test
    fun `maxReleaseChars 不影响扣留语义`() {
        // 未闭合 fence：预算再大也零放行（扣留优先于上限）
        val snap = "文字\n\n" + "```" + "kotlin\nval a = 1"
        val r0 = SafePrefixGate.releaseLength(snap, 0)
        assertEquals(r0, SafePrefixGate.releaseLength(snap, 0, maxReleaseChars = 50))
    }

    // #556（2026-10-17）触发形状钉子：行首硬停字符（[ 系链接开定界面）+ 无空行
    // 段落 → 流中全额扣留（releasedTotal 恒 0 = reveal 永零高的取证签名）。
    // 设计语义：[ 可为嵌套链接开符（[x] y](url) 后缀重释义），保守扣留正确；
    // 去路=空行毕业/终态 flush（pilot 层 contentTerminal，非 gate 职责）。
    @Test
    fun `行首硬停字符无空行段落流中全额扣留`() {
        // mock 战役原始形状：[sid] 前缀行、空格拼接（无任何换行）
        val snap = "[ab2c3d] Step 1: frame-local pairing holds the card top. " +
            "[ab2c3d] Step 2: frame-local pairing holds the card top. "
        assertEquals(0, rel(snap))
        // 增长后仍全额扣留（held 随快照增长，releasedTotal 不动）
        assertEquals(0, rel(snap + "[ab2c3d] Step 3: more text here. "))
    }

    @Test
    fun `硬停字符行经空行毕业定案`() {
        // 扣留去路①：最后一个空行块之前的内容无条件放行
        val snap = "[ab2c3d] Step 1: closed paragraph. " + "\n\n" + "[ab2c3d] Step 2: open tail. "
        assertEquals(snap.indexOf("\n\n") + 2, rel(snap))
    }
}
