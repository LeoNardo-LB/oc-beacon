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

        // SubcomposeLayout uses a "probe" + "final" composition; both create
        // semantics nodes. The probe is measured with infinite max-width so its
        // URL node overflows — pick the node that fits within the container.
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

        // Cell text from markdown includes trailing space ("delta "); use
        // substring match. SubcomposeLayout probe + final create multiple
        // nodes — pick the placed one within container bounds.
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
