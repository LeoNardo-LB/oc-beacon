package dev.leonardo.ocbeacon.ui.screens.chat.components

import com.mikepenz.markdown.model.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlanAnchorTest {

    private suspend fun parse(text: String): State.Success {
        val normalized = dev.leonardo.ocbeacon.ui.screens.chat.markdown.normalizeForRender(text, isUser = false)
        val flow = com.mikepenz.markdown.model.parseMarkdownFlow(normalized)
        return withTimeout(10_000) { flow.first { it is State.Success } as State.Success }
    }

    @Test
    fun anchorMatchesRangeFirstBlockPrefix() = runBlocking(Dispatchers.Default) {
        val text = buildString {
            append("# 标题\n\n")
            repeat(6) { i -> append("## 第" + (i + 1) + "节\n\n第" + (i + 1) + "节的内容段落，足够长以累积字符预算。\n\n") }
        }
        val st = parse(text)
        val plan = computeChunkPlan("p_t", st, minChars = 100, targetChars = 150)!!
        assertEquals(plan.ranges.size, plan.rangeAnchors.size)
        val kids = st.node.children
        plan.ranges.forEachIndexed { idx, r ->
            val s0 = kids[r.first].startOffset
            val rawSlice = st.content.substring(s0, minOf(s0 + 48, st.content.length))
            val trimmedFrom = s0 + (rawSlice.length - rawSlice.trimStart().length)
            val expected = st.content
                .substring(trimmedFrom, minOf(trimmedFrom + 32, st.content.length))
                
            assertEquals(expected, plan.rangeAnchors[idx])
        }
    }

    @Test
    fun anchorsAppearInSourceOrder() = runBlocking(Dispatchers.Default) {
        val text = buildString {
            append("# 总览\n\n")
            repeat(8) { i -> append("## 章节" + (i + 1) + "\n\n章节" + (i + 1) + "正文段落，内容足够长。标记词 ANCHOR" + (i + 1) + "X。\n\n") }
        }
        val st = parse(text)
        val plan = computeChunkPlan("p_o", st, minChars = 100, targetChars = 140)!!
        val pairs = plan.rangeAnchors.mapIndexed { i, a -> i to a }.filter { it.second.isNotBlank() }
        val offsets = pairs.map { it.second.let { a -> st.content.indexOf(a) } }
        java.io.File("/tmp/anchor-diag.txt").appendText("\nanchors2=" + plan.rangeAnchors.map { "[" + it.take(20) + "]" } + "\noffsets=" + offsets)
        assertTrue(offsets.all { it >= 0 })
        java.io.File("/tmp/anchor-diag.txt").writeText("ranges=" + plan.ranges + "\nanchors=" + plan.rangeAnchors.map { "[" + it + "]" }.joinToString("\n") + "\noffsets=" + offsets)
        assertTrue(offsets.zipWithNext().all { (a, b) -> a < b })
    }
}
