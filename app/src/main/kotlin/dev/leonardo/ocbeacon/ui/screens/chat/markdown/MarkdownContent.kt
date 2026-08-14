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
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.State

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

// ============ Markdown 预处理 ============

// #135（D2-L44）：正则顶层预编译——流式渲染每 token 重组时不再现场编译
private val SINGLE_NEWLINE_REGEX = Regex("(?<!\n)\n(?!\n)")
private val TABLE_AFTER_TEXT_REGEX = Regex("""([^\n]*[^\n|])\n([ \t]*\|[^\n]*\|)\n([ \t]*\|[-:\s|]+\|)""")

/**
 * 最小化的 Markdown 预处理——让 Mikepenz Handle 原生解析。
 * 仅保留用户消息的换行规范化。
 * 自定义 HTML 检测和表格格式修复已移除，以避免破坏渲染的误报。
 */
internal fun normalizeMarkdown(raw: String, isUser: Boolean): String {
    // 规范化 Windows 换行符（\r\n → \n）。Windows 上的 opencode server
    // 在 Markdown 文本中返回 \r\n，这可能破坏 GFM 表格解析
    //（\r 可能被当作单元格内容而非行尾）。
    var result = raw.replace("\r\n", "\n").replace("\r", "\n")

    // 确保 GFM 表格前有一个空行。
    // JetBrains markdown 解析器仅在块边界处检测表格；
    // 紧跟在段落后的表格（无空行）会渲染为纯文本。
    result = ensureBlankLineBeforeGfmTables(result)

    if (!isUser) return result
    // 用户消息：单个 \n 在 Markdown 中不换行（软换行）。
    return result.replace(SINGLE_NEWLINE_REGEX, "\n\n")
}

/**
 * 确保 GFM 表格前有一个空行。
 *
 * JetBrains markdown 解析器仅在块边界处检测 GFM 表格。
 * 当 LLM 在段落之后直接输出表格（无空行）时，
 * `|` 字符会被当作字面文本，表格无法渲染。
 *
 * 模式：非表格行 \n |表头| \n |---| → 非表格行 \n\n |表头| \n |---|
 */
private fun ensureBlankLineBeforeGfmTables(text: String): String {
    // 匹配：不以 | 结尾的行，后跟表格表头行（以 | 开头），
    // 再跟分隔行（仅含 -、:、空格和 | 的 |）。
    return text.replace(TABLE_AFTER_TEXT_REGEX) { m ->
        "${m.groupValues[1]}\n\n${m.groupValues[2]}\n${m.groupValues[3]}"
    }
}

/**
 * 渲染归一化（2026-08-13 提取）：与 MarkdownContent 渲染完全一致的文本预处理
 * ——预解析（parseMarkdownFlow）必须用同一归一化结果，否则解析出的 AST 与
 * 实际渲染内容不一致（换行差异 → 高度不同——实测 214 vs 331）。
 */
internal fun normalizeForRender(raw: String, isUser: Boolean): String =
    normalizeTaskListMarkers(normalizeMarkdown(raw, isUser))

@Composable
internal fun MarkdownContent(
    markdown: String,
    textColor: Color,
    isUser: Boolean,
    @Suppress("UNUSED_PARAMETER") customFontSize: String? = null,
    @Suppress("UNUSED_PARAMETER") immediate: Boolean = false,
    // 2026-08-12 根治：跳转预渲染——外部（MessageCardUser）创建的 MarkdownState
    //（用于 await 解析完成信号）；null = 内部自建（常规渲染路径）。
    overrideState: MarkdownState? = null,
    // 2026-08-13 根本方案：跳转目标预解析结果（parseMarkdownFlow 后台解析的
    // State）——非空时直接用 Markdown(state) 重载渲染（无解析等待/loading）
    preParsedState: State? = null,
) {
    // 注意：customFontSize 和 immediate 保留是为了调用点兼容性
    //（PartContent / ReasoningBlock 仍传入它们），但有意不使用
    // ——排版/密度现在由 LocalChatDensity 驱动，且 Mikepenz Markdown
    // 同步解析，因此 immediate 标志无效果。
    val normalizedMarkdown = remember(markdown, isUser) {
        normalizeForRender(markdown, isUser)
    }

    val isAmoled = isAmoledTheme()
    val density = LocalChatDensity.current
    val tokens = density.typography
    val spacing = density.spacing

    // 行内代码前景色——在不使用不透明背景的情况下保持文本可读。
    val inlineCodeFg = when {
        isAmoled -> MaterialTheme.colorScheme.onSurface
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.primary
    }
    // 代码块区分背景色。
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

    // 显式链接处理器——在此作用域捕获 LocalUriHandler，确保
    // 即使在 SelectionContainer 内部，链接点击也使用
    // 自定义 UriHandler（由 ChatScreen 提供）。
    val uriHandler = LocalUriHandler.current
    val linkListener = remember(uriHandler) {
        LinkInteractionListener { link ->
            val url = (link as? LinkAnnotation.Url)?.url
            if (url != null) uriHandler.openUri(url)
        }
    }

    // components 闭包捕获 linkColor/typography/textColor。键必须包含它们：
    // 主题切换时颜色变化 → 重建闭包 → 内部 AnnotatedString 用新颜色重建，
    // 否则切换主题后文字颜色停留在旧主题（暗色浅色在亮色背景下"过曝"）。
    val components = remember(density, isUser, linkListener, linkColor, textColor) {
        markdownComponents(
            text = { model ->
                val settings = annotatorSettings(linkInteractionListener = linkListener)
                // AnnotatedString 内嵌 style 颜色（buildMarkdownAnnotatedString pushStyle），
                // remember 键必须含颜色，否则主题切换后命中缓存颜色不更新。
                val result = remember(model.content, model.node, model.typography.text.color, linkColor) {
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
                val result = remember(model.content, model.node, model.typography.text.color, linkColor) {
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
                val result = remember(model.content, model.node, model.typography.h1.color, linkColor) {
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

    // 2026-08-13 根本方案：预解析结果存在时直接用 Markdown(state) 重载渲染
    //（无解析等待/loading——内容直接是最终状态）
    if (preParsedState != null) {
        Markdown(
            state = preParsedState,
            colors = colors,
            typography = typography,
            components = components,
            padding = padding,
            animations = markdownAnimations(animateTextSize = { this }),
            imageTransformer = Coil3ImageTransformerImpl,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    val markdownState = overrideState ?: rememberMarkdownState(
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


