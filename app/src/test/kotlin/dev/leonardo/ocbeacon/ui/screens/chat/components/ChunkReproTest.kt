package dev.leonardo.ocbeacon.ui.screens.chat.components

import com.mikepenz.markdown.model.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class ChunkReproTest {
    @Test
    fun repro() = runBlocking(Dispatchers.Default) {
        val text = java.io.File("/tmp/giant.md").readText()
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
        Unit
    }
}
