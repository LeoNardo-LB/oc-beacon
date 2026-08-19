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
    /** #120（D2-08）：可点击项的绝对字符区间（AnnotatedString 坐标系）。
     *  Link 区间取自 buildMarkdownAnnotatedString 的链接 span（精确 offset，
     *  不受重复文本干扰）；CodePath 区间取自顺序搜索（顺序推进 searchFrom，
     *  与既有语义一致但以真实命中为准）。 */
    val ranges: List<IntRange>,
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
                val url = dest.getUnescapedTextInNode(content)
                val rawText = textNode.getUnescapedTextInNode(content)
                val linkText = rawText.removeSurrounding("[", "]")
                if (linkText.isNotEmpty() && url.isNotEmpty()) {
                    items.add(ClickableItem.Link(linkText, url))
                }
            }
        } else if (n.type == MarkdownElementTypes.CODE_SPAN) {
            val raw = n.getUnescapedTextInNode(content)
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
    // #120（D2-08）：为全部 items 建立绝对区间。Link 的区间直接取自
    // 链接 span 的 start/end——精确 offset，重复文本/同文名不再错位；
    // CodePath 沿用顺序搜索区间。items 与 ranges 一一对应（未命中为 EMPTY）。
    //
    // 双路定位：① Link 优先匹配链接 span（精确 offset，annotator 配置了
    // 链接样式时可用——按文档序消费 + 文本一致性校验）；② span 不可用
    //（最小 annotator / 样式未注册）或 CodePath → 顺序文本搜索，全局
    // 游标单调推进——重复文本的同名项依次消费各自的出现位置，不再
    // 全部命中第一个（旧 indexOf 逐项独立搜索的错位根因）。
    var spanIdx = 0
    var textCursor = 0
    val ranges = items.map { item ->
        var matched = IntRange.EMPTY
        if (item is ClickableItem.Link) {
            var i = spanIdx
            while (i < rawAnnotated.spanStyles.size) {
                val sp = rawAnnotated.spanStyles[i]
                if (sp.end <= rawAnnotated.text.length &&
                    rawAnnotated.text.substring(sp.start, sp.end) == item.text
                ) {
                    matched = sp.start..<(sp.end)
                    spanIdx = i + 1
                    break
                }
                i++
            }
        }
        if (matched != IntRange.EMPTY) {
            textCursor = maxOf(textCursor, matched.last + 1)
            matched
        } else {
            val idx = annotated.text.indexOf(item.text, textCursor)
            if (idx >= 0) {
                textCursor = idx + item.text.length
                idx..<(idx + item.text.length)
            } else IntRange.EMPTY
        }
    }
    return ClickableMarkdownResult(annotated, items, ranges)
}

/**
 * 为 [ClickableMarkdownResult] 中的可点击项注册点击手势处理。
 *
 * 使用 [TextLayoutResult] 将点击位置映射到 item → [uriHandler].openUri()。
 * #120（D2-08）：按预计算的绝对区间判定命中——不再 indexOf 搜索，
 * 重复文本段落不再点击错位/误开链接。
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
                // 区间倒序查首个命中（嵌套/重叠时取最内层=最晚出现的样式）
                for (i in result.ranges.indices) {
                    val r = result.ranges[i]
                    if (offset in r) {
                        when (val item = result.items[i]) {
                            is ClickableItem.Link -> uriHandler.openUri(item.url)
                            is ClickableItem.CodePath -> uriHandler.openUri(item.text)
                        }
                        return@detectTapGestures
                    }
                }
            }
        }
}