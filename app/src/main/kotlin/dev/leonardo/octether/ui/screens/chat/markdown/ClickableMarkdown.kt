package dev.leonardo.octether.ui.screens.chat.markdown

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
import dev.leonardo.octether.domain.model.LinkClassifier
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
 * Extract clickable items from a markdown AST node:
 * - [text](url) markdown links → ClickableItem.Link
 * - `code` inline code that looks like a path → ClickableItem.CodePath
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
 * Builds an [AnnotatedString] with clickable file paths overlaid on top of
 * standard markdown link rendering.
 *
 * 1. [buildMarkdownAnnotatedString] produces base text with standard links.
 * 2. Walks the AST for [CODE_SPAN] nodes; [LinkClassifier.isLikelyFilePath] filters them.
 * 3. Overlays underline + [linkColor] style on matched code paths.
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
 * Registers tap-gesture handling for clickable items in the [ClickableMarkdownResult].
 *
 * Uses [TextLayoutResult] to map tap position → item → [uriHandler].openUri().
 * Must be called from a @Composable context.
 */
@Composable
internal fun Modifier.clickableMarkdown(
    result: ClickableMarkdownResult,
    layoutResultProvider: () -> TextLayoutResult?,
    uriHandler: UriHandler,
): Modifier {
    // NOTE: the old implementation had an empty .clickable { } modifier here that
    // intercepted long-press gestures, preventing SelectionContainer from showing
    // the copy/select toolbar. Only .pointerInput is needed for link tap handling.
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
