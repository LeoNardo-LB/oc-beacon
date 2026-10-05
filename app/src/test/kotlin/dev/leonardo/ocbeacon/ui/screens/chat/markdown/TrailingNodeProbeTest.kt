package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Test

/** #517 取证探针：尾随换行是否产生尾随空段（PartContent 路径 24px 悬案）。 */
class TrailingNodeProbeTest {
    private fun childrenOf(raw: String) =
        MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(raw).children

    @Test
    fun probeTrailingChildren() {
        val cases = mapOf(
            "fence + single trailing \\n" to "prose\n\n```bash\ncode\n```\n",
            "fence + double trailing \\n" to "prose\n\n```bash\ncode\n```\n\n",
            "fence no trailing newline" to "prose\n\n```bash\ncode\n```",
            "plain text + trailing \\n" to "hello world\n",
        )
        for ((label, raw) in cases) {
            val kids = childrenOf(raw)
            println("== $label  (children=${kids.size})")
            kids.forEachIndexed { i, n ->
                val text = raw.substring(n.startOffset, n.endOffset)
                println("  [$i] ${n.type} span=${n.startOffset}..${n.endOffset} text=${text.take(24).replace("\n", "\\n")}${if (text.length > 24) "…" else ""} blank=${text.isBlank()}")
            }
        }
    }

    @Test
    fun probeNormalizedTurnTail() {
        // 真实轮尾（DB 导出，文件存在时才跑）：normalizeForRender 后的末 8 个顶层子节点
        val f = java.io.File("/tmp/card1/turnA.txt")
        if (!f.exists()) {
            println("skip: /tmp/card1/turnA.txt absent")
            return
        }
        val real = f.readText()
        val normalized = normalizeForRender(real, isUser = false)
        println("real len=${real.length} normalized len=${normalized.length} normalized tail=${normalized.takeLast(24).replace("\n", "\\n")}")
        val kids = childrenOf(normalized)
        println("children=${kids.size}")
        kids.takeLast(8).forEachIndexed { i, n ->
            val text = normalized.substring(n.startOffset, n.endOffset)
            println("  [${kids.size - 8 + i}] ${n.type} span=${n.startOffset}..${n.endOffset} text=${text.take(28).replace("\n", "\\n")}${if (text.length > 28) "…" else ""}")
        }
    }
}
