package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
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
import com.mikepenz.markdown.model.parseMarkdownFlow
import org.intellij.markdown.MarkdownTokenTypes
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn

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
        html.replaceFirst(CLOSE_HEAD_REGEX, "$styleBlock</head>")
    } else {
        "<head>$styleBlock</head>$html"
    }
}

// ============ Markdown 预处理 ============

// #135（D2-L44）+ #106-4：正则顶层预编译——流式渲染每 token 重组时不再现场编译
private val CLOSE_HEAD_REGEX = Regex("(?i)</head>")
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
internal fun ensureBlankLineBeforeGfmTables(text: String): String {
    // 2026-08-26 流式卡顿根因修复（simpleperf 实证 ICU RegexMatcher 占主线程
    // CPU 8.35% 全进程第一）：该正则对全文扫描，流式期间每 48ms 全量重跑。
    // 模式必然含 '|'（组 2/3 的表格行）——无 '|' 的文本（essay/纯段落常态）
    // 不可能命中，native contains 扫描短路，正则零成本。
    if (!text.contains('|')) return text
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
    splitOversizedParagraphs(normalizeTaskListMarkers(normalizeMarkdown(raw, isUser)))

// ============ 超长段落空行化（2026-08-20 第二轮滚动卡顿 C-F1） ============

/**
 * 单个普通段落超过此字符量时，段内单换行升级为空行（每行独立成块）。
 *
 * 实测（真机 DB + org.intellij.markdown 0.7.5 JVM 复核）：LLM 的巨型清单
 * （"1 - one\n2 - two\n…"）不构成 GFM 列表（数字后是空格+短横线，非
 * "1."/"1)"）→ 整个 11-13 万字符是一个顶层 PARAGRAPH → MarkdownChunking
 * 只按顶层块边界切 → 对最坏消息完全失效（129K 单段 = 单个 3000 行
 * StaticLayout，首组合 40-120ms 原样保留——长消息内滚动卡顿根因）。
 *
 * 空行化后每行成为独立 PARAGRAPH 块 → 现有分片全链路（预解析 → chunk
 * plan → LazyItem 区间）自然生效。阈值与 CHUNK_MIN_CHARS 同量级：
 * 只有真正会被分片的消息才发生视觉变化（段内行距略增），普通消息零影响。
 *
 * 保护：围栏代码块 / 表格 / 列表 / 引用 / 缩进续行 / 标题行不参与
 * （它们的行结构有语义，拆开会破坏渲染）。
 */
private const val SPLIT_PARAGRAPH_THRESHOLD_CHARS = 3000

/** 段落行分类：仅"普通文本行"参与空行化（见 [SPLIT_PARAGRAPH_THRESHOLD_CHARS]）。 */
private fun isPlainParagraphLine(line: String): Boolean {
    val t = line.trimStart()
    if (t.isEmpty()) return false
    if (t.startsWith("|")) return false                      // 表格行
    if (t.startsWith("#")) return false                      // 标题
    if (t.startsWith("```") || t.startsWith("~~~")) return false // 围栏代码围栏行
    if (t.startsWith(">")) return false                      // 引用
    if (line.startsWith("    ") || line.startsWith("\t")) return false // 缩进代码/列表续行
    if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) return false // 无序列表
    if (isOrderedListItem(t)) return false // 有序列表（1. / 1)）——手写检查（2026-08-26 流式卡顿：免每行 ICU 正则）
    return true
}

internal val OrderedListItemRegex = Regex("^\\d{1,9}[.)]\\s")

/** [OrderedListItemRegex] 的无正则等价（splitOversizedParagraphs 每行调用，流式热路径）。 */
private fun isOrderedListItem(t: String): Boolean {
    var i = 0
    var digits = 0
    while (i < t.length && t[i] in '0'..'9') { i++; digits++ }
    if (digits == 0 || digits > 9) return false
    if (i >= t.length) return false
    val c = t[i]
    if (c != '.' && c != ')') return false
    val next = t.getOrNull(i + 1) ?: return false
    return next == ' ' || next == '\t' || next == '\u000B' || next == '' || next == '\r'
}

/**
 * 超长段落空行化：连续普通文本行构成一个候选段；总字符 ≥
 * [SPLIT_PARAGRAPH_THRESHOLD_CHARS] 时段内行间补空行（单换行 → 空行）。
 * 其余内容原样保留。
 */
internal fun splitOversizedParagraphs(text: String): String {
    if (text.length < SPLIT_PARAGRAPH_THRESHOLD_CHARS) return text
    val lines = text.split("\n")
    val out = StringBuilder(text.length + lines.size)
    var runStart = -1
    var runChars = 0
    var inFence = false
    var i = 0
    while (i <= lines.size) {
        val line = if (i < lines.size) lines[i] else ""
        val isFence = line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")
        // 候选段终止条件：空行 / 非普通行 / 围栏边界
        val plain = !inFence && i < lines.size && !isFence && isPlainParagraphLine(line)
        if (plain) {
            if (runStart < 0) {
                runStart = i
                runChars = 0
            }
            runChars += line.length + 1
            i++
            continue
        }
        // 冲刷候选段
        if (runStart >= 0) {
            val runEnd = i // 不含
            if (runChars >= SPLIT_PARAGRAPH_THRESHOLD_CHARS && runEnd - runStart >= 2) {
                for (j in runStart until runEnd) {
                    out.append(lines[j])
                    if (j < runEnd - 1) out.append("\n\n") // 行间空行：独立成块
                }
            } else {
                for (j in runStart until runEnd) {
                    out.append(lines[j])
                    if (j < runEnd - 1) out.append('\n')
                }
            }
            runStart = -1
            runChars = 0
        }
        if (isFence) inFence = !inFence
        if (i < lines.size) {
            out.append(line)
            if (i < lines.size - 1) out.append('\n')
        }
        i++
    }
    return out.toString()
}

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
    // 2026-08-20 fling 巨帧根治：块级分片渲染区间（顶层 AST children 的
    // [from, to) 子列表）——null = 全量（原行为）。仅与 preParsedState 组合使用。
    blockRange: IntRange? = null,
    // #246 时序排序：与 blockRange 对应的每片首块文本锚点（MarkdownChunking
    // 计划期记录）。非空时 chunkSuccessSlot 在当前 AST 中按锚点重定位区间起点，
    // 索引漂移自愈 + 片间顺序由锚点在 AST 中的出现序保证（确定性排序）。
    blockAnchor: String? = null,
    // 2026-08-22 滚动巨帧根治：非流式 fallback 的异步解析（见
    // rememberAsyncMarkdownState）——流式内容必须 false（48ms 批处理 +
    // conflate 铁律路径，rememberMarkdownState 保留）。
    asyncParse: Boolean = false,
) {
    // 注意：customFontSize 和 immediate 保留是为了调用点兼容性
    //（PartContent / ReasoningBlock 仍传入它们），但有意不使用
    // ——排版/密度现在由 LocalChatDensity 驱动，且 Mikepenz Markdown
    // 同步解析，因此 immediate 标志无效果。
    //
    // 2026-08-22 滚动巨帧根治：归一化从组合路径移除——原 remember{} 在主线程
    // 对全文跑正则+切段（20K 字符级多条批量 = vsync→input 90ms 巨帧，真机
    // framestats 实证），且 preParsedState 命中路径纯属浪费（预解析已在后台
    // 归一化过）。现在仅流式/同步 fallback 路径归一化（流式内容单条增量，
    // 成本可控）；asyncParse fallback 在后台归一化（parseAsync 内）。

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

    // 2026-08-20 C-F4：remember（分片后一条消息 = N 个 MarkdownContent，
    // 每次重组重建 N 份配置对象——typography 含 15+ TextStyle.copy；53 chunk
    // 慢滚实测 p95 恶化 4ms）。key 含全部依赖（主题/密度/颜色角色）。
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
    val tableBackground = MaterialTheme.colorScheme.surfaceContainerLow
    val colors = remember(textColor, codeBlockBg, inlineCodeBg, dividerColor, tableBackground) {
        com.mikepenz.markdown.model.DefaultMarkdownColors(
            text = textColor,
            codeBackground = codeBlockBg,
            inlineCodeBackground = inlineCodeBg,
            dividerColor = dividerColor,
            tableBackground = tableBackground,
        )
    }

    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
        color = textColor,
        fontSize = tokens.bodyFontSize,
        lineHeight = tokens.bodyLineHeight,
    )

    val mt = MaterialTheme.typography // C-F4：composable 读取提出，remember lambda 内不可读
    val typography = remember(textColor, codeBlockFg, inlineCodeFg, linkColor, density, tokens, mt) {
        com.mikepenz.markdown.model.DefaultMarkdownTypography(
        h1 = mt.titleLarge.copy(
            color = textColor,
            fontSize = tokens.h1.fontSize,
            lineHeight = tokens.h1.lineHeight,
            fontWeight = tokens.h1.fontWeight,
        ),
        h2 = mt.titleLarge.copy(
            color = textColor,
            fontSize = tokens.h2.fontSize,
            lineHeight = tokens.h2.lineHeight,
            fontWeight = tokens.h2.fontWeight,
        ),
        h3 = mt.titleMedium.copy(
            color = textColor,
            fontSize = tokens.h3.fontSize,
            lineHeight = tokens.h3.lineHeight,
            fontWeight = tokens.h3.fontWeight,
        ),
        h4 = mt.titleSmall.copy(
            color = textColor,
            fontSize = tokens.h4.fontSize,
            lineHeight = tokens.h4.lineHeight,
            fontWeight = tokens.h4.fontWeight,
        ),
        h5 = mt.bodyMedium.copy(
            color = textColor,
            fontSize = tokens.h5.fontSize,
            lineHeight = tokens.h5.lineHeight,
            fontWeight = tokens.h5.fontWeight,
        ),
        h6 = mt.bodyMedium.copy(
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
    }

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
                // #246 修复（2026-08-27）：库的 buildMarkdownAnnotatedString walker
                // 无 ATX_CONTENT 分支（源码 AnnotatedStringKtx 只处理 PARAGRAPH/
                // TEXT/EMPH/LINK 等）——标题节点产出空串，H1 退化成「只剩分隔
                // 线」。标题文本直接取节点 ATX_CONTENT 子节点转义文本（与库默认
                // MarkdownHeader 的 MarkdownText(contentChildType=ATX_CONTENT) 一致）。
                val h1Text = remember(model.content, model.node) {
                    val atx = model.node.children.firstOrNull { it.type == MarkdownTokenTypes.ATX_CONTENT }
                    val raw = (atx ?: model.node).getUnescapedTextInNode(model.content).toString()
                    raw.trim().trimStart('#').trim()
                }
                var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                Column {
                    MarkdownBasicText(
                        text = if (result.annotatedString.isNotBlank()) result.annotatedString else AnnotatedString(h1Text),
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

    // padding 工厂返回 private data class 无法直接构造；工厂本身是纯小对象
    // 分配（无 TextStyle.copy 风暴），保留每次调用（typography 才是重组大头）
    val padding = markdownPadding(
        block = spacing.block,
        list = 0.dp,
        listItemTop = 2.dp,
        listItemBottom = spacing.listItemBottom,
        listIndent = 4.dp,
    )

    // C-F4：animations 每次 Markdown() 调用重建新 lambda —— 提为单实例
    val animations = remember { com.mikepenz.markdown.model.DefaultMarkdownAnimation(animateTextSize = { this }) }

    // 2026-08-13 根本方案：预解析结果存在时直接用 Markdown(state) 重载渲染
    //（无解析等待/loading——内容直接是最终状态）
    if (preParsedState != null) {
        // 2026-08-20 分片：blockRange 非空时只渲染 [from, to) 区间的顶层块
        //（其余块由同 turn 的相邻 chunk item 渲染——引用式链接在解析期已
        // 写入 referenceLinkHandler，拆开渲染不破坏跨块引用）。
        if (blockRange != null) {
            Markdown(
                state = preParsedState,
                colors = colors,
                typography = typography,
                components = components,
                padding = padding,
                animations = animations,
                imageTransformer = Coil3ImageTransformerImpl,
                modifier = Modifier.fillMaxWidth(),
                success = chunkSuccessSlot(blockRange, blockAnchor),
            )
        } else {
            Markdown(
                state = preParsedState,
                colors = colors,
                typography = typography,
                components = components,
                padding = padding,
                animations = animations,
                imageTransformer = Coil3ImageTransformerImpl,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }

    // 2026-08-22：非流式长文本 fallback 异步化——库的 rememberMarkdownState
    // 在主线程同步 parseBlocking（字节码实证 parse$2 内联 parseBlocking，无
    // flowOn）；预解析 miss 时冷态快滑巨帧 84ms（framestats vsync→input）。
    // asyncParse=true 时归一化+解析全程 Default 线程，主线程仅收 StateFlow 发射。
    val markdownState = overrideState ?: if (asyncParse) {
        rememberAsyncMarkdownState(markdown, isUser)
    } else {
        // 流式/同步路径：归一化保留在此分支（流式单条增量成本可控）
        val normalizedForLib = remember(markdown, isUser) { normalizeForRender(markdown, isUser) }
        rememberMarkdownState(
            content = normalizedForLib,
            retainState = true,
        )
    }

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

/**
 * 非流式 fallback 的异步 MarkdownState（2026-08-22 滚动巨帧根治）。
 *
 * 背景：Markdown(markdownState=...) 可组合项仅 state.collectAsState() 渲染，
 * 从不调用 parse()、也不读 links（0.43.0 字节码核对）——自建实现只需提供
 * state 流。解析经 parseMarkdownFlow.flowOn(Default) 全程后台，主线程零
 * 解析成本（对比库 rememberMarkdownState 的主线程 parseBlocking）。
 *
 * 仅用于非流式内容（remember(content) 单次解析）：流式增长内容必须走库的
 * rememberMarkdownState（snapshotFlow+conflate 增量路径——SSE 滚动铁律）。
 */
private class AsyncMarkdownStateImpl : MarkdownState {
    private val _state = MutableStateFlow<State>(State.Loading())
    override val state: StateFlow<State> = _state.asStateFlow()
    private val _links = MutableStateFlow<Map<String, String>>(emptyMap())
    override val links: StateFlow<Map<String, String>> = _links.asStateFlow()
    private var lastContent: String = ""

    /** 后台归一化+解析并持续回写状态（suspend 到完成；全程 Default 线程）。 */
    suspend fun parseAsync(content: String, isUser: Boolean) {
        lastContent = content
        lastIsUser = isUser
        kotlinx.coroutines.flow.flow {
            emit(normalizeForRender(content, isUser))
        }.flowOn(Dispatchers.Default).collect { normalized ->
            parseMarkdownFlow(normalized).flowOn(Dispatchers.Default).collect { st ->
                _state.value = st
            }
        }
    }

    /** 接口必需；Markdown 可组合项不调用（防御实现：后台归一化+解析取终态）。 */
    override suspend fun parse(): State {
        kotlinx.coroutines.flow.flow {
            emit(normalizeForRender(lastContent, lastIsUser))
        }.flowOn(Dispatchers.Default).collect { normalized ->
            parseMarkdownFlow(normalized).flowOn(Dispatchers.Default).collect { st ->
                _state.value = st
            }
        }
        return _state.value
    }

    private var lastIsUser: Boolean = false
}

@Composable
private fun rememberAsyncMarkdownState(content: String, isUser: Boolean): MarkdownState {
    val impl = remember { AsyncMarkdownStateImpl() }
    LaunchedEffect(impl, content, isUser) {
        impl.parseAsync(content, isUser)
    }
    return impl
}

/**
 * 2026-08-20 分片：构造只渲染 [from, to) 顶层块的 success 槽。
 * null 区间 = 默认全量渲染（null 槽 → 库默认 MarkdownSuccess）。
 *
 * #246 时序排序（2026-08-27）：提供 [anchor] 时，先在当前 AST 顶层块中
 * 扫描锚点签名重定位区间起点（索引漂移自愈），再按 AST 固有顺序渲染到
 * 计划终点——保证多片拼接与源文块顺序一致；找不到锚点 → 回退纯索引。
 */
private fun chunkSuccessSlot(
    blockRange: IntRange,
    anchor: String? = null,
): @Composable (State.Success, com.mikepenz.markdown.compose.components.MarkdownComponents, Modifier) -> Unit {
    val rng = blockRange
    return { st, comps, mod ->
        androidx.compose.foundation.layout.Column(mod) {
            val kids = st.node.children
            var from = rng.first.coerceIn(0, kids.size)
            val to = (rng.last + 1).coerceAtMost(kids.size)
            if (!anchor.isNullOrEmpty() && kids.isNotEmpty()) {
                // 锚点重定位：在当前 AST 中找首块签名最贴近的块（前缀匹配，
                // 归一空白差异容忍）。只在计划索引附近 ±窗口扫描保持 O(1) 性质。
                val norm = anchor.replace(Regex("\\s+"), " ")
                val searchFrom = (rng.first - 4).coerceAtLeast(0)
                val searchTo = (rng.first + 5).coerceAtMost(kids.size)
                var hit = -1
                for (i in searchFrom until searchTo) {
                    val startOff = kids[i].startOffset.coerceIn(0, st.content.length)
                    // #246：候选封顶在块自身 endOffset——越界切片会把下一块文字
                    // 算进候选，空白块也能「沾光」命中（实证 c1 from=22）。
                    val endOff = minOf(
                        startOff + anchor.length + 8,
                        kids[i].endOffset.coerceIn(0, st.content.length),
                        st.content.length,
                    )
                    val candidate = st.content.substring(startOff, endOff).replace(Regex("\\s+"), " ").trim()
                    // #246：候选必须非空——空白块的空串会被 norm.contains("") 恒真截胡，
                    // 锚点重定位落进空白块（实证 c1 from=22 = 空白块，白渲染一个 0 高元素）。
                    if (norm.isNotEmpty() && candidate.isNotEmpty() && (candidate.contains(norm.take(20)) || norm.contains(candidate))) {
                        hit = i; break
                    }
                }
                if (hit >= 0) from = hit
            }
            // #246 插桩：成功槽实际渲染窗（DEBUG-only，行为零变化）
            if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                val firstSig = kids.getOrNull(from)?.let { n ->
                    val s0 = n.startOffset.coerceIn(0, st.content.length)
                    val e0 = minOf(n.endOffset, s0 + 24).coerceIn(0, st.content.length)
                    if (e0 > s0) st.content.substring(s0, e0).replace("\n", " ") else "?"
                }
                android.util.Log.w("ChunkDiag", "slot key-range=" + rng.first + ".." + rng.last +
                    " from=" + from + " to=" + to + " first=[" + firstSig + "]")
            }
            for (i in from until to) {
                com.mikepenz.markdown.compose.MarkdownElement(kids[i], comps, st.content)
            }
        }
    }
}
