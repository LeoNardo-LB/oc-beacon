package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL as GFMCell
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #429 L0-②：[tableTsv]/[cellPlainText] 的机械不变量（JVM 纯逻辑）。
 * 整表复制入口的正确性依赖：列序、制表符分隔、行内标记剥离。
 */
class TableTsvTest {

    private fun cellsOf(node: org.intellij.markdown.ast.ASTNode) =
        node.children.filter { it.type == GFMCell }

    private fun parseTable(md: String): Pair<String, List<TableRow>> {
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(md)
        val table = tree.children.first()
        return md to listOf(
            TableRow(isHeader = true, rowIndex = -1, cells = cellsOf(table.children.first())),
            TableRow(isHeader = false, rowIndex = 0, cells = cellsOf(table.children.last())),
        )
    }

    @Test
    fun `两列表格导出为制表符分隔行`() {
        val md = "| a | b |\n|---|---|\n| 1 | 2 |"
        val (content, rows) = parseTable(md)
        val tsv = tableTsv(content, rows, 2)
        val lines = tsv.split("\n")
        assertEquals(2, lines.size)
        assertEquals("a\tb", lines[0])
        assertEquals("1\t2", lines[1])
    }

    @Test
    fun `行内标记剥离为显示文字`() {
        val md = "| **粗体** | `code` | [链接](https://x.y) |\n|---|---|---|\n| plain | x | y |"
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(md)
        val table = tree.children.first()
        val header = cellsOf(table.children.first())
        assertEquals("粗体", cellPlainText(md, header[0]))
        assertEquals("code", cellPlainText(md, header[1]))
        assertEquals("链接", cellPlainText(md, header[2]))
    }

    @Test
    fun `列数上限截断超宽行`() {
        val md = "| a | b | c |\n|---|---|---|\n| 1 | 2 | 3 |"
        val (content, rows) = parseTable(md)
        val line = tableTsv(content, rows, 2).split("\n")[0]
        assertEquals(2, line.split("\t").size)
    }
}
