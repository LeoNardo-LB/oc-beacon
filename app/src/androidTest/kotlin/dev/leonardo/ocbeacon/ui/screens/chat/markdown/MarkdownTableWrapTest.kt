package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MarkdownTableWrapTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setMarkdown(md: String) {
        composeRule.setContent {
            MaterialTheme {
                MarkdownContent(markdown = md, textColor = Color.Black, isUser = false)
            }
        }
    }

    @Test
    fun long_url_cell_wraps_and_does_not_overflow_container() {
        val longUrl = "https://example.com/" + "a".repeat(120)
        setMarkdown("| col |\n| --- |\n| $longUrl |")
        composeRule.waitForIdle()

        // SubcomposeLayout 使用 "probe" + "final" 两次组合；二者都会
        // 创建 semantics 节点。probe 以无限最大宽度测量，因此其 URL
        // 节点会溢出 —— 选取能放入容器内的那个节点。
        val rootWidth = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        val nodes = composeRule.onAllNodesWithText(longUrl, substring = true).fetchSemanticsNodes()
        assertTrue("should find cell node(s) for url", nodes.isNotEmpty())
        val cell = nodes.first { it.boundsInRoot.right <= rootWidth + 0.5f }
        assertTrue(
            "cell right edge ${cell.boundsInRoot.right} must not exceed root width $rootWidth",
            cell.boundsInRoot.right <= rootWidth + 0.5f
        )
        assertTrue("cell must wrap to multiple lines", cell.boundsInRoot.height > 1f)
    }

    @Test
    fun regular_two_column_table_does_not_overflow() {
        setMarkdown("| name | value |\n| --- | --- |\n| alpha | beta |\n| gamma | delta |")
        composeRule.waitForIdle()

        // 来自 markdown 的单元格文本带有尾随空格（"delta "），因此使用
        // 子串匹配。SubcomposeLayout 的 probe + final 会创建多个节点 ——
        // 选取位于容器边界内、已被放置的那个节点。
        val rootWidth = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        val deltaNodes = composeRule.onAllNodesWithText("delta", substring = true).fetchSemanticsNodes()
        assertTrue("should find delta cell(s)", deltaNodes.isNotEmpty())
        val lastCell = deltaNodes.first { it.boundsInRoot.right <= rootWidth + 0.5f }
        assertTrue(
            "cell right edge ${lastCell.boundsInRoot.right} must not exceed root width $rootWidth",
            lastCell.boundsInRoot.right <= rootWidth + 0.5f
        )
    }
}
