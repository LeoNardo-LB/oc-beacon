package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.mikepenz.markdown.annotator.AnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import dev.leonardo.ocbeacon.domain.model.LinkClassifier
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType

internal sealed interface ClickableItem {
    val text: String
    data class Link(override val text: String, val url: String) : ClickableItem
    data class CodePath(override val text: String) : ClickableItem
}

internal data class ClickableMarkdownResult(
    val annotatedString: AnnotatedString,
    val items: List<ClickableItem>,
)

/**
 * 从 markdown AST 节点提取可点击项：
 * - [text](url) markdown 链接 → ClickableItem.Link
 * - `code` 看起来像路径的行内代码 → ClickableItem.CodePath
 */
private fun extractClickableItems(content: String, node: ASTNode): List<ClickableItem> {
    val items = mutableListOf<ClickableItem>()
    fun walk(n: ASTNode) {
        if (n.type == MarkdownElementTypes.INLINE_LINK) {
            val dest = n.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
            val textNode = n.findChildOfType(MarkdownElementTypes.LINK_TEXT)
            if (dest != null && textNode != null) {
                val url = dest.getUnescapedTextInNode(content).toString()
                val rawText = textNode.getUnescapedTextInNode(content).toString()
                val linkText = rawText.removeSurrounding("[", "]")
                if (linkText.isNotEmpty() && url.isNotEmpty()) {
                    items.add(ClickableItem.Link(linkText, url))
                }
            }
        } else if (n.type == MarkdownElementTypes.CODE_SPAN) {
            val raw = n.getUnescapedTextInNode(content).toString()
            val codeText = raw.trim('`').trim()
            if (codeText.isNotEmpty() && LinkClassifier.isLikelyFilePath(codeText)) {
                items.add(ClickableItem.CodePath(codeText))
            }
        }
        n.children.forEach { walk(it) }
    }
    walk(node)
    return items
}

/**
 * 构建一个 [AnnotatedString]，在标准 markdown 链接渲染之上叠加可点击文件路径。
 *
 * 1. [buildMarkdownAnnotatedString] 生成带标准链接的基础文本。
 * 2. 遍历 AST 查找 [CODE_SPAN] 节点；用 [LinkClassifier.isLikelyFilePath] 过滤。
 * 3. 在匹配的代码路径上叠加下划线 + [linkColor] 样式。
 */
internal fun buildClickableMarkdown(
    content: String,
    node: ASTNode,
    style: TextStyle,
    annotatorSettings: AnnotatorSettings,
    linkColor: Color,
): ClickableMarkdownResult {
    val rawAnnotated = content.buildMarkdownAnnotatedString(
        textNode = node,
        style = style,
        annotatorSettings = annotatorSettings,
    )
    val items = extractClickableItems(content, node)
    val codePaths = items.filterIsInstance<ClickableItem.CodePath>()
    val annotated = if (codePaths.isEmpty()) rawAnnotated else {
        buildAnnotatedString {
            append(rawAnnotated.text)
            rawAnnotated.spanStyles.forEach { range ->
                addStyle(range.item, range.start, range.end)
            }
            var searchFrom = 0
            for (cp in codePaths) {
                val idx = rawAnnotated.text.indexOf(cp.text, searchFrom)
                if (idx >= 0) {
                    addStyle(
                        SpanStyle(
                            textDecoration = TextDecoration.Underline,
                            color = linkColor,
                        ),
                        idx, idx + cp.text.length,
                    )
                    searchFrom = idx + cp.text.length
                }
            }
        }
    }
    return ClickableMarkdownResult(annotated, items)
}

/**
 * 为 [ClickableMarkdownResult] 中的可点击项注册点击手势处理。
 *
 * 使用 [TextLayoutResult] 将点击位置映射到 item → [uriHandler].openUri()。
 * 必须在 @Composable 上下文中调用。
 */
@Composable
internal fun Modifier.clickableMarkdown(
    result: ClickableMarkdownResult,
    layoutResultProvider: () -> TextLayoutResult?,
    uriHandler: UriHandler,
): Modifier {
    // 注意：旧实现在此处有一个空的 .clickable { } 修饰符，
    // 它会拦截长按手势，阻止 SelectionContainer 显示复制/选择工具栏。
    // 链接点击处理只需要 .pointerInput。
    return this
        .pointerInput(result.annotatedString) {
            detectTapGestures { pos ->
                val layout = layoutResultProvider() ?: return@detectTapGestures
                val offset = layout.getOffsetForPosition(pos)
                val text = result.annotatedString.text
                var searchFrom = 0
                for (item in result.items) {
                    val idx = text.indexOf(item.text, searchFrom)
                    if (idx >= 0 && offset >= idx && offset < idx + item.text.length) {
                        when (item) {
                            is ClickableItem.Link -> uriHandler.openUri(item.url)
                            is ClickableItem.CodePath -> uriHandler.openUri(item.text)
                        }
                        return@detectTapGestures
                    }
                    if (idx >= 0) searchFrom = idx + item.text.length
                }
            }
        }
}
