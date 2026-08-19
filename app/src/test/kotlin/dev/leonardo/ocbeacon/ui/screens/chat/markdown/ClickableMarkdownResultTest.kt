package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

class ClickableMarkdownResultTest {

    @Test
    fun `ClickableItem Link has correct properties`() {
        val item = ClickableItem.Link("click here", "https://example.com")
        assertEquals("click here", item.text)
        assertEquals("https://example.com", (item as ClickableItem.Link).url)
    }

    @Test
    fun `ClickableItem CodePath has correct properties`() {
        val item = ClickableItem.CodePath("src/Main.kt")
        assertEquals("src/Main.kt", item.text)
    }

    @Test
    fun `ClickableMarkdownResult holds annotated string and items`() {
        val result = ClickableMarkdownResult(
            annotatedString = androidx.compose.ui.text.AnnotatedString("test"),
            items = listOf(ClickableItem.CodePath("Foo.kt")),
            ranges = listOf(IntRange.EMPTY),
        )
        assertNotNull(result.annotatedString)
        assertEquals(1, result.items.size)
        assertTrue(result.items[0] is ClickableItem.CodePath)
    }

    // ============ #120（D2-08）：重复文本链接区间精确性 ============

    /**
     * D2-08 回归：两个同文本链接指向不同 URL——旧 indexOf 实现两个点击都
     * 命中第一个。修复后区间取自链接 span，各归其位。
     * 验证入口：buildClickableMarkdown 产出的 ranges 与 items 一一对应，
     * 同文本两链接的区间互不重叠。
     */
    @Test
    fun `D2-08 duplicate-text links get distinct non-overlapping ranges`() {
        val markdown = "see [docs](https://a.com) and again [docs](https://b.com)"
        val parser = org.intellij.markdown.parser.MarkdownParser(org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor())
        val tree = parser.buildMarkdownTreeFromString(markdown)
        val result = buildClickableMarkdown(
            content = markdown,
            node = tree,
            style = androidx.compose.ui.text.TextStyle(),
            // annotatorSettings() 工厂是 @Composable（读 LocalMarkdownTypography 等），
            // JUnit 函数内无法调用；直接构造库内实现类（referenceLinkHandler/
            // linkInteractionListener 默认 null，markdownAnnotator() 非 composable）
            annotatorSettings = com.mikepenz.markdown.annotator.DefaultAnnotatorSettings(
                linkTextSpanStyle = androidx.compose.ui.text.TextLinkStyles(
                    style = androidx.compose.ui.text.SpanStyle(
                        color = androidx.compose.ui.graphics.Color.Blue,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    ),
                ),
                codeSpanStyle = androidx.compose.ui.text.SpanStyle(),
                annotator = com.mikepenz.markdown.model.markdownAnnotator(),
            ),
            linkColor = androidx.compose.ui.graphics.Color.Blue,
        )
        val links = result.items.filterIsInstance<ClickableItem.Link>()
        assertEquals("two same-text links extracted", 2, links.size)
        assertEquals("https://a.com", links[0].url)
        assertEquals("https://b.com", links[1].url)
        val linkRanges = result.ranges.filter { it != IntRange.EMPTY }
        assertEquals("both links located", 2, linkRanges.size)
        val (r1, r2) = linkRanges[0] to linkRanges[1]
        assertTrue("first range starts before second", r1.first < r2.first)
        assertTrue("ranges do not overlap", r1.last < r2.first)
        // 区间文本即链接文本（精确性）
        assertEquals("docs", result.annotatedString.text.substring(r1.first, r1.last + 1))
        assertEquals("docs", result.annotatedString.text.substring(r2.first, r2.last + 1))
    }
}
