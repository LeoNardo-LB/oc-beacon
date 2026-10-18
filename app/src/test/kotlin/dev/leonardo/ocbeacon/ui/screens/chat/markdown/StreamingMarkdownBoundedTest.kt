package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #552 根修单元锚：流式渲染的元素级文本 + 偏移重基坐标等价。
 *
 * 核心不变量：对任意 AST 节点，boundedElementModel 产出的
 * (model.content, model.node) 与库组件在全文档坐标下的提取**逐字符等价**——
 * `node.getTextInNode(doc) == shifted.getTextInNode(model.content)`。
 * 组件层因此零感知（它们只按绝对偏移从 model.content 取文本）。
 */
class StreamingMarkdownBoundedTest {

    private val typo = DefaultMarkdownTypography(
        h1 = TextStyle(), h2 = TextStyle(), h3 = TextStyle(), h4 = TextStyle(),
        h5 = TextStyle(), h6 = TextStyle(), text = TextStyle(), code = TextStyle(),
        inlineCode = TextStyle(), quote = TextStyle(), paragraph = TextStyle(),
        ordered = TextStyle(), bullet = TextStyle(), list = TextStyle(),
        textLink = TextLinkStyles(SpanStyle(), SpanStyle()), table = TextStyle(),
        alertTitle = TextStyle(),
    )

    private fun parse(doc: String): ASTNode =
        MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(doc)

    @Test
    fun `元素级提取与全文档坐标逐字符等价`() {
        val doc = ("first para with **bold** and `code` span\n\n# Head line\n\n" +
            "second para\n\n- li one\n- li two\n\n```kotlin\nfun x()\n```\n\n" +
            "| a | b |\n|---|---|\n| 1 | 2 |\n\n> quote line\n\ntail para")
        val tops = parse(doc).children
        assertTrue("顶层块不足: ${tops.size}", tops.size >= 10)
        for (node in tops) {
            val m = boundedElementModel(doc, node, typo)
            assertEquals(doc.substring(node.startOffset, node.endOffset), m.content)
            assertEquals(0, m.node.startOffset)
            assertEquals(m.content.length, m.node.endOffset)
            // 坐标等价（组件视角零差异的核心判据）
            assertEquals(
                node.getTextInNode(doc).toString(),
                m.node.getTextInNode(m.content).toString(),
            )
            m.node.children.forEach { c ->
                assertTrue(c.startOffset >= 0 && c.endOffset <= m.content.length)
            }
        }
    }

    @Test
    fun `段落元素 model 内容恰为该段文本`() {
        val doc = "aaa\n\nbbb\n\nccc"
        val paras = parse(doc).children.filter { it.type == MarkdownElementTypes.PARAGRAPH }
        assertEquals(3, paras.size)
        val m0 = boundedElementModel(doc, paras[0], typo)
        assertEquals("aaa", m0.node.getTextInNode(m0.content).toString())
        val m2 = boundedElementModel(doc, paras[2], typo)
        assertEquals("ccc", m2.node.getTextInNode(m2.content).toString())
    }

    @Test
    fun `ShiftedASTNode parent 为 null 且 children 实例稳定`() {
        val doc = "a\n\nb"
        val p = parse(doc).children.first()
        val m = boundedElementModel(doc, p, typo)
        assertNull(m.node.parent)
        assertSame(m.node.children, m.node.children)
        assertEquals(p.type, m.node.type)
        assertEquals(p.children.size, m.node.children.size)
    }

    @Test
    fun `越界 coerce 防失配帧不抛`() {
        class FakeNode(
            override val startOffset: Int,
            override val endOffset: Int,
        ) : ASTNode {
            override val type: IElementType = IElementType("FAKE")
            override val parent: ASTNode? = null
            override val children: List<ASTNode> = emptyList()
        }

        val content = "abcdef"
        assertEquals("cdef", boundedElementModel(content, FakeNode(2, 99), typo).content)
        assertEquals("", boundedElementModel(content, FakeNode(9, 12), typo).content)
    }

    @Test
    fun `巨流保留线性核算`() {
        // r1036 形态（2600 段 × ~60ch）：全部元素 model.content 总长 == 文档全长
        // （库实现的保留为 Σ 毕业时刻全文档快照 = O(N²)；本实现 O(N)）
        val doc = (1..2600).joinToString("\n\n") {
            "Turn thought %d: the anchor holds the card top while streaming.".format(it)
        }
        val tops = parse(doc).children
        assertTrue(tops.size >= 2500)
        var sum = 0
        tops.forEach { sum += boundedElementModel(doc, it, typo).content.length }
        assertEquals(doc.length, sum)
    }
}
