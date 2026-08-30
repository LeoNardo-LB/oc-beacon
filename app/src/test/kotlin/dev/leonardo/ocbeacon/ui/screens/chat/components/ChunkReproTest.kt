package dev.leonardo.ocbeacon.ui.screens.chat.components

import com.mikepenz.markdown.model.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * #261：原版依赖一次性调试夹具 /tmp/giant.md（未入库，缺文件即 FileNotFoundException
 * 连累全量单测报红）。改为测试内自造结构化巨文档（>CHUNK_MIN_CHARS=3000），
 * 保持原回归意图：巨文档解析成功 + 分块计划非空 + 分块不以空白块开头。
 */
class ChunkReproTest {
    @Test
    fun repro() = runBlocking(Dispatchers.Default) {
        val text = giantMarkdown()
        val normalized = dev.leonardo.ocbeacon.ui.screens.chat.markdown.normalizeForRender(text, isUser = false)
        println("RAW=" + text.length + " NORM=" + normalized.length)
        val flow = com.mikepenz.markdown.model.parseMarkdownFlow(normalized)
        val st = withTimeout(10_000) { flow.first { it is State.Success } as State.Success }
        val kids = st.node.children
        println("kids=" + kids.size)
        var idx5 = -1
        kids.forEachIndexed { i, n ->
            val s0 = n.startOffset.coerceIn(0, st.content.length)
            val s1 = n.endOffset.coerceIn(0, st.content.length)
            val s = st.content.substring(s0, s1)
            if (s.contains("索引下推") && idx5 < 0) idx5 = i
        }
        println("first block containing 索引下推 = " + idx5)
        val plan = computeChunkPlan("x", st, RenderSupplyCoordinator.CHUNK_MIN_CHARS, RenderSupplyCoordinator.CHUNK_TARGET_CHARS)
        org.junit.Assert.assertNotNull("超过 CHUNK_MIN_CHARS 的巨文档应产生分块计划", plan)
        println("ranges=" + (plan?.ranges?.joinToString { it.first.toString() + ".." + it.last } ?: "null"))
        plan?.ranges?.forEachIndexed { ci, r ->
            val first = kids[r.first]
            val s0 = first.startOffset.coerceIn(0, st.content.length)
            val s1 = minOf(first.endOffset, first.startOffset + 60).coerceIn(0, st.content.length)
            val head = if (s1 > s0) st.content.substring(s0, s1).replace("\n", " ") else "?"
            println("chunk" + ci + " blocks=" + (r.last - r.first + 1) + " first=" + head.take(45))
            org.junit.Assert.assertTrue("chunk" + ci + " 不得以空白块开头", head.isNotBlank())
        }
        Unit
    }

    /** 自造结构化巨文档：约 300 节 × ~300 字符 ≈ 90KB，含标题/段落/代码块/列表。 */
    private fun giantMarkdown(): String {
        val sb = StringBuilder()
        for (i in 0 until 300) {
            sb.append("# 第 ").append(i).append(" 节\n\n")
            sb.append("本节包含用于分块回归的填充段落，覆盖中英文混排与标点。The quick brown fox jumps over the lazy dog. ")
            sb.append("重复句子以拉高长度，使文档规模超过 CHUNK_MIN_CHARS=3000 阈值并触发多块计划。")
            sb.append("额外补充句：分块起点不得落在空白块上，渲染供给协调器按目标预算切分顶层块。\n\n")
            if (i == 150) {
                sb.append("本段包含索引下推标记词，用于验证分块起点定位是否正确落在含该词的块。\n\n")
            }
            sb.append("```kotlin\nfun sample").append(i).append("() = ").append(i).append("\n```\n\n")
            sb.append("- 列表项 A ").append(i).append("\n- 列表项 B ").append(i).append("\n- 列表项 C ").append(i).append("\n\n")
        }
        return sb.toString()
    }
}
