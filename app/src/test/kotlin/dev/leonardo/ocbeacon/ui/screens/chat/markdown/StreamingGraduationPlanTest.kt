package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #442 R2 分片唤醒（批次 A1）——毕业计划纯函数的不变量测试。
 *
 * 钉住的性质（违反任一 = 装配层键换血/闪烁回归）：
 * 单调只前进 / 冻结 append-only / 区间无缝从 0 起 / 切点全落空行块边界 /
 * 门槛与上限 / 非前缀重置 / 无空行不毕业 / 拼接恒等。
 */
class StreamingGraduationPlanTest {

    private fun para(tag: String, lines: Int = 3) = "# $tag\n" + ("内容行。\n".repeat(lines)) + "\n"

    /** 5 个空行分隔块。 */
    private val snapshot = (1..5).joinToString("") { para("B$it") }

    private fun plan(
        released: Int,
        previous: StreamingGraduation? = null,
        min: Int = 100,
        max: Int = 1000,
    ) = planStreamingGraduation(snapshot, released, previous, min, max)

    @Test
    fun `无块边界不毕业（通篇无空行）`() {
        val s = "一整段没有空行的长文本".repeat(50)
        val p = planStreamingGraduation(s, s.length, minFreezeChars = 10)
        assertEquals(0, p.tailFrom)
        assertTrue(p.chunks.isEmpty())
    }

    @Test
    fun `增量不足门槛不毕业`() {
        val p0 = plan(snapshot.length, min = snapshot.length + 1)
        assertEquals(0, p0.tailFrom)
        // 已有前置毕业后，新积累不足门槛同样不动
        val p1 = plan(released = 200, min = 100)
        assertTrue(p1.chunks.isNotEmpty())
        val p2 = planStreamingGraduation(
            snapshot + para("B6"), released = p1.tailFrom + 10, previous = p1, minFreezeChars = 100, maxChunkChars = 1000,
        )
        assertEquals(p1.tailFrom, p2.tailFrom)
        assertEquals(p1.chunks, p2.chunks)
    }

    @Test
    fun `毕业只落在空行块边界且区间无缝从零起`() {
        val p = plan(released = snapshot.length, min = 100)
        assertTrue(p.chunks.isNotEmpty())
        assertEquals(p.chunks.last().to, p.tailFrom)
        var expect = 0
        for (c in p.chunks) {
            assertEquals(expect, c.from)
            assertTrue(c.to > c.from)
            // 切点前必须以 ≥2 连续换行收束（首块 from=0 除外）
            if (c.from > 0) {
                assertTrue("cut at ${c.from} not blank-line boundary", isBlankRunEnd(snapshot, c.from))
            }
            expect = c.to
        }
        assertEquals(p.tailFrom, expect)
    }

    @Test
    fun `冻结 append-only（旧块永不改写）+ tailFrom 单调`() {
        // 长快照：多次 release 推进产生多次毕业
        val long = (1..20).joinToString("") { para("L$it") }
        var p = planStreamingGraduation(long, 150, null, minFreezeChars = 100)
        val seen = mutableListOf(p)
        for (rel in listOf(300, 380, long.length / 2 + 380, long.length)) {
            p = planStreamingGraduation(long, rel, p, minFreezeChars = 100)
            seen += p
        }
        for (k in seen.indices) {
            for (j in k + 1 until seen.size) {
                val older = seen[k].chunks
                val newer = seen[j].chunks
                assertTrue("older chunks must be prefix", newer.take(older.size) == older)
                assertTrue(seen[j].tailFrom >= seen[k].tailFrom)
            }
        }
        assertTrue(seen.last().tailFrom > seen.first().tailFrom)
    }

    @Test
    fun `上限打包（贪心，块不跨 chunk；单块超限独占）`() {
        // max=每块略小 → 每块独立成 chunk；含一个超限巨块（无内部空行）
        val big = "巨".repeat(300)
        val s = para("A") + big + "\n\n" + para("C") + para("D")
        val p = planStreamingGraduation(s, s.length, minFreezeChars = 10, maxChunkChars = 40)
        assertTrue(p.chunks.size >= 3)
        for (c in p.chunks) {
            val size = c.to - c.from
            // 超限仅当该块自身无内部边界（尾部空行 run 属块间分隔，允许）
            if (size > 40) {
                val text = s.substring(c.from, c.to).trimEnd('\n')
                assertTrue(text.indexOf("\n\n") < 0)
            }
        }
    }

    @Test
    fun `非前缀重置（snapshot 缩短 released 回退）`() {
        val p1 = plan(released = 300, min = 100)
        assertTrue(p1.chunks.isNotEmpty())
        val reset = planStreamingGraduation("短新串", 3, previous = p1)
        assertEquals(0, reset.tailFrom)
        assertTrue(reset.chunks.isEmpty())
        val reset2 = plan(released = p1.tailFrom - 1, previous = p1)
        assertEquals(0, reset2.tailFrom)
        assertTrue(reset2.chunks.isEmpty())
    }

    @Test
    fun `released 钳位与 held 区隔离（tailFrom 不越过 released）`() {
        // released 落在块中间：边界只取此前最后一个空行 run 后
        val midBlock = snapshot.indexOf("内容行", snapshot.indexOf("B3")) // B3 块内部
        val p = plan(released = midBlock, min = 10)
        assertTrue(p.tailFrom <= midBlock)
        assertTrue(isBlankRunEnd(snapshot, p.tailFrom) || p.tailFrom == 0)
    }

    @Test
    fun `拼接恒等（chunks 连接 + 尾块 = 快照前缀）`() {
        val p = plan(released = snapshot.length, min = 100, max = 200)
        val frozenText = p.chunks.joinToString("") { snapshot.substring(it.from, it.to) }
        val tailText = snapshot.substring(p.tailFrom)
        assertEquals(snapshot, frozenText + tailText)
    }

    private fun isBlankRunEnd(s: String, pos: Int): Boolean {
        // pos 前是 ≥2 连续 \n 且 pos 处不是 \n（run 之后）
        var n = 0
        var i = pos - 1
        while (i >= 0 && s[i] == '\n') { n++; i-- }
        return n >= 2 && (pos >= s.length || s[pos] != '\n')
    }

    // ===== #516（2026-10-05）：围栏原子性——围栏内空行不设切点 =====

    @Test
    fun `围栏内空行不切块（整围栏独占一块允许超限）`() {
        motive("#516 真机形态缩影：163 行单围栏 34 内空行——切点全失效→单块整体，一块代码一段渲染")
        val body = (1..30).joinToString("") { "fun f$it() {}\n\n" }
        val s = "```kotlin\n" + body + "```\n\n结语段落。\n"
        val g = planStreamingGraduation(s, s.length, minFreezeChars = 10, maxChunkChars = 50)
        // 围栏内零切点：唯一冻结块整体覆盖开闭栏（>>max=50 走巨块超限独占豁免）
        assertEquals(1, g.chunks.size)
        val open = s.indexOf("```kotlin")
        val close = s.indexOf("\n```", open) + 4 // 闭栏行尾
        assertTrue(g.chunks[0].from == 0 && g.chunks[0].to >= close)
    }

    @Test
    fun `散文围栏混排切点只落围栏外`() {
        motive("切点合法域收窄：围栏开闭之间的任何位置不得为中间切点——两侧独立解析=代码碎段")
        val s = "第一段散文。\n\n第二段散文。\n\n```kotlin\nfun a() {}\n\nfun b() {}\n```\n\n结尾段。\n"
        val g = planStreamingGraduation(s, s.length, minFreezeChars = 10, maxChunkChars = 20)
        val open = s.indexOf("```kotlin")
        val close = s.indexOf("\n```", open) + 1
        g.chunks.dropLast(1).forEach { c ->
            assertTrue("切点落入围栏内部: ${c.to}", c.to !in (open + 1)..close)
        }
        // 拼接恒等不破（零内容变异）
        assertEquals(s.substring(0, g.tailFrom), g.chunks.joinToString("") { s.substring(it.from, it.to) })
    }

    @Test
    fun `未闭围栏整体驻尾块（围栏前缀散文照常毕业）`() {
        motive("开栏期间不毕业围栏内容（闭栏前不可拆）；围栏之前的前缀散文边界不受影响")
        val s = "前言。\n\n```kotlin\nfun a() {}\n\nfun b() {}"
        val g = planStreamingGraduation(s, s.length, minFreezeChars = 3, maxChunkChars = 1000)
        assertEquals(listOf(FrozenChunk(0, 5)), g.chunks) // 仅"前言。\n\n"毕业
        assertEquals(5, g.tailFrom) // 围栏全部驻尾
    }

    private fun motive(m: String) = println("[MOTIVE] $m")
}
