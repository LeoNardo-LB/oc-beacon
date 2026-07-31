package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState

import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ChatDensity
import dev.leonardo.ocbeacon.ui.theme.CodeTypography
import dev.leonardo.ocbeacon.ui.theme.LocalChatDensity
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.spacing
import dev.leonardo.ocbeacon.ui.theme.typography

private val HtmlDocumentHintRegex = Regex("(?is)<!doctype\\s+html\\b|<\\s*html\\b")
private val HtmlTagRegex = Regex("(?is)<\\s*/?\\s*[a-z][^>]*>")

internal fun looksLikeHtmlPayload(text: String): Boolean {
    if (text.isBlank()) return false
    if (HtmlDocumentHintRegex.containsMatchIn(text)) return true
    return HtmlTagRegex.findAll(text).take(12).count() >= 6
}

internal fun normalizeHtmlForEmbeddedPreview(html: String): String {
    if (html.isBlank()) return html
    val overrideCss = """
        html, body {
          margin: 0 !important;
          padding: 8px !important;
          min-height: auto !important;
          height: auto !important;
        }
        body {
          display: block !important;
          align-items: flex-start !important;
          justify-content: flex-start !important;
          overflow: auto !important;
        }
        .container {
          align-items: flex-start !important;
          justify-content: flex-start !important;
          height: auto !important;
          min-height: auto !important;
          width: 100% !important;
          margin: 0 !important;
        }
    """.trimIndent()

    val styleBlock = "<style>$overrideCss</style>"
    return if (html.contains("</head>", ignoreCase = true)) {
        html.replaceFirst(Regex("(?i)</head>"), "$styleBlock</head>")
    } else {
        "<head>$styleBlock</head>$html"
    }
}

// ============ Markdown Preprocessing ============

/**
 * Minimal Markdown preprocessing — let Mikepenz Handle parsing natively.
 * Only user message newline normalization remains.
 * Custom HTML detection and table format fixing removed to avoid
 * false-positives that broke rendering.
 */
internal fun normalizeMarkdown(raw: String, isUser: Boolean): String {
    // Normalize Windows line endings (\r\n → \n). opencode server on Windows
    // returns \r\n in Markdown text, which can break GFM table parsing
    // (the \r may be treated as cell content instead of line ending).
    var result = raw.replace("\r\n", "\n").replace("\r", "\n")

    // Ensure GFM tables are preceded by a blank line.
    // The JetBrains markdown parser only detects tables at block boundaries;
    // a table directly following a paragraph (no blank line) renders as plain text.
    result = ensureBlankLineBeforeGfmTables(result)

    if (!isUser) return result
    // User messages: single \n doesn't break in Markdown (soft break).
    return result.replace(Regex("(?<!\n)\n(?!\n)"), "\n\n")
}

/**
 * Ensures GFM tables are preceded by a blank line.
 *
 * The JetBrains markdown parser only detects GFM tables at block boundaries.
 * When an LLM outputs a table directly after a paragraph (no blank line),
 * the `|` characters are treated as literal text and the table doesn't render.
 *
 * Pattern: non-table line \n |header| \n |---| → non-table line \n\n |header| \n |---|
 */
private fun ensureBlankLineBeforeGfmTables(text: String): String {
    // Match: a line NOT ending with |, followed by a table header line (starts with |),
    // followed by a separator line (| with only -, :, spaces, and |).
    val tableAfterText = Regex("""([^\n]*[^\n|])\n([ \t]*\|[^\n]*\|)\n([ \t]*\|[-:\s|]+\|)""")
    return text.replace(tableAfterText) { m ->
        "${m.groupValues[1]}\n\n${m.groupValues[2]}\n${m.groupValues[3]}"
    }
}

@Composable
internal fun MarkdownContent(
    markdown: String,
    textColor: Color,
    isUser: Boolean,
    @Suppress("UNUSED_PARAMETER") customFontSize: String? = null,
    @Suppress("UNUSED_PARAMETER") immediate: Boolean = false,
) {
    // NOTE: customFontSize & immediate are retained for call-site compatibility
    // (PartContent / ReasoningBlock still pass them) but are intentionally unused
    // — typography/density is now driven by LocalChatDensity, and Mikepenz Markdown
    // parses synchronously so the immediate flag has no effect.
    val normalizedMarkdown = remember(markdown, isUser) {
        normalizeTaskListMarkers(normalizeMarkdown(markdown, isUser))
    }

    val isAmoled = isAmoledTheme()
    val density = LocalChatDensity.current
    val tokens = density.typography
    val spacing = density.spacing

    // Inline code foreground — keep text legible without opaque background.
    val inlineCodeFg = when {
        isAmoled -> MaterialTheme.colorScheme.onSurface
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.primary
    }
    // Code block distinct backgrounds.
    val codeBlockBg = when {
        isAmoled -> MaterialTheme.colorScheme.surfaceContainerHighest
        isUser -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val codeBlockFg = when {
        isAmoled -> MaterialTheme.colorScheme.onSurface
        isUser -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val inlineCodeBg = when {
        isAmoled -> MaterialTheme.colorScheme.surfaceContainerHighest
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = AlphaTokens.SELECTED)
        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = AlphaTokens.FAINT)
    }
    val linkColor = when {
        isAmoled -> MaterialTheme.colorScheme.primary
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.primary
    }

    val colors = markdownColor(
        text = textColor,
        codeBackground = codeBlockBg,
        inlineCodeBackground = inlineCodeBg,
        dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
        tableBackground = MaterialTheme.colorScheme.surfaceContainerLow,
    )

    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
        color = textColor,
        fontSize = tokens.bodyFontSize,
        lineHeight = tokens.bodyLineHeight,
    )

    val typography = markdownTypography(
        h1 = MaterialTheme.typography.titleLarge.copy(
            color = textColor,
            fontSize = tokens.h1.fontSize,
            lineHeight = tokens.h1.lineHeight,
            fontWeight = tokens.h1.fontWeight,
        ),
        h2 = MaterialTheme.typography.titleLarge.copy(
            color = textColor,
            fontSize = tokens.h2.fontSize,
            lineHeight = tokens.h2.lineHeight,
            fontWeight = tokens.h2.fontWeight,
        ),
        h3 = MaterialTheme.typography.titleMedium.copy(
            color = textColor,
            fontSize = tokens.h3.fontSize,
            lineHeight = tokens.h3.lineHeight,
            fontWeight = tokens.h3.fontWeight,
        ),
        h4 = MaterialTheme.typography.titleSmall.copy(
            color = textColor,
            fontSize = tokens.h4.fontSize,
            lineHeight = tokens.h4.lineHeight,
            fontWeight = tokens.h4.fontWeight,
        ),
        h5 = MaterialTheme.typography.bodyMedium.copy(
            color = textColor,
            fontSize = tokens.h5.fontSize,
            lineHeight = tokens.h5.lineHeight,
            fontWeight = tokens.h5.fontWeight,
        ),
        h6 = MaterialTheme.typography.bodyMedium.copy(
            color = textColor.copy(alpha = tokens.h6.alpha),
            fontSize = tokens.h6.fontSize,
            lineHeight = tokens.h6.lineHeight,
            fontWeight = tokens.h6.fontWeight,
        ),
        text = bodyStyle,
        code = CodeTypography.copy(
            color = codeBlockFg,
            fontSize = tokens.codeFontSize,
            lineHeight = tokens.codeLineHeight,
        ),
        inlineCode = CodeTypography.copy(
            color = inlineCodeFg,
            fontSize = tokens.codeFontSize,
            fontWeight = FontWeight.Medium,
        ),
        quote = bodyStyle.copy(
            color = textColor.copy(alpha = AlphaTokens.MEDIUM),
            fontStyle = FontStyle.Italic,
        ),
        paragraph = bodyStyle,
        ordered = bodyStyle,
        bullet = bodyStyle,
        list = bodyStyle,
        table = bodyStyle.copy(
            fontSize = tokens.tableFontSize,
            lineHeight = tokens.codeLineHeight,
        ),
        textLink = TextLinkStyles(
            style = bodyStyle.copy(
                color = linkColor,
                fontWeight = FontWeight.Medium,
            ).toSpanStyle()
        ),
    )

    // Explicit link handler — captures LocalUriHandler at THIS scope, ensuring
    // the custom UriHandler (provided by ChatScreen) is used for link clicks
    // even inside SelectionContainer.
    val uriHandler = LocalUriHandler.current
    val linkListener = remember(uriHandler) {
        LinkInteractionListener { link ->
            val url = (link as? LinkAnnotation.Url)?.url
            if (url != null) uriHandler.openUri(url)
        }
    }

    val components = remember(density, isUser, linkListener) {
        markdownComponents(
            text = { model ->
                val settings = annotatorSettings(linkInteractionListener = linkListener)
                val result = remember(model.content, model.node) {
                    buildClickableMarkdown(
                        content = model.content,
                        node = model.node,
                        style = model.typography.text,
                        annotatorSettings = settings,
                        linkColor = linkColor,
                    )
                }
                var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                MarkdownBasicText(
                    text = result.annotatedString,
                    style = model.typography.text,
                    onTextLayout = { layoutResult = it },
                    modifier = Modifier.clickableMarkdown(
                        result = result,
                        layoutResultProvider = { layoutResult },
                        uriHandler = uriHandler,
                    ),
                )
            },
            paragraph = { model ->
                val settings = annotatorSettings(linkInteractionListener = linkListener)
                val result = remember(model.content, model.node) {
                    buildClickableMarkdown(
                        content = model.content,
                        node = model.node,
                        style = model.typography.text,
                        annotatorSettings = settings,
                        linkColor = linkColor,
                    )
                }
                var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                MarkdownBasicText(
                    text = result.annotatedString,
                    style = model.typography.text,
                    onTextLayout = { layoutResult = it },
                    modifier = Modifier.clickableMarkdown(
                        result = result,
                        layoutResultProvider = { layoutResult },
                        uriHandler = uriHandler,
                    ),
                )
            },
            heading1 = { model ->
                val settings = annotatorSettings(linkInteractionListener = linkListener)
                val result = remember(model.content, model.node) {
                    buildClickableMarkdown(
                        content = model.content,
                        node = model.node,
                        style = model.typography.h1,
                        annotatorSettings = settings,
                        linkColor = linkColor,
                    )
                }
                var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                Column {
                    MarkdownBasicText(
                        text = result.annotatedString,
                        style = typography.h1,
                        onTextLayout = { layoutResult = it },
                        modifier = Modifier.clickableMarkdown(
                            result = result,
                            layoutResultProvider = { layoutResult },
                            uriHandler = uriHandler,
                        ),
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(top = spacing.block),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
                    )
                }
            },
            table = { model ->
                SimpleMarkdownTable(model.content, model.node, model.typography.table, uriHandler, linkColor)
            },
        )
    }

    val padding = markdownPadding(
        block = spacing.block,
        list = 0.dp,
        listItemTop = 2.dp,
        listItemBottom = spacing.listItemBottom,
        listIndent = 4.dp,
    )

    val markdownState = rememberMarkdownState(
        content = normalizedMarkdown,
        retainState = true,
    )

    Markdown(
        markdownState = markdownState,
        colors = colors,
        typography = typography,
        components = components,
        padding = padding,
        animations = markdownAnimations(animateTextSize = { this }),
        imageTransformer = Coil3ImageTransformerImpl,
        modifier = Modifier.fillMaxWidth(),
    )
}


