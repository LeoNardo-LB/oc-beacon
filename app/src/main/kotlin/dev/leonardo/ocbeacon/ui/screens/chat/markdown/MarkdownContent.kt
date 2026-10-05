package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
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
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.parseMarkdown
import com.mikepenz.markdown.model.parseMarkdownFlow
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
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
 * 归一化共享核心（#471③ 流式/完结同源铁律的落点，spec
 * docs/specs/2026-09-30-471-3-streaming-normalization-unification-design.md §3.1）：
 * CRLF→LF + GFM 表格前空行 + 数学降级。assistant/user 通用，无身份分支——
 * 三者皆为「放行安全」变换：回改点全部落在 SafePrefixGate 扣留区（双美元
 * 定界符行/表头行/含反斜杠行皆活动标记行），已放行前缀的字节不因后续
 * 到达而改变（逐变换矩阵证明见 spec §3.3）。
 */
internal fun normalizeMarkdownCore(raw: String): String {
    // 规范化 Windows 换行符（\r\n → \n）。Windows 上的 opencode server
    // 在 Markdown 文本中返回 \r\n，这可能破坏 GFM 表格解析
    //（\r 可能被当作单元格内容而非行尾）。
    // 快路径：无 \r 整串跳过两次 replace（流式热路径每批一次）。
    var result = if (raw.indexOf('\r') < 0) raw
    else raw.replace("\r\n", "\n").replace("\r", "\n")

    // 确保 GFM 表格前有一个空行。
    // JetBrains markdown 解析器仅在块边界处检测表格；
    // 紧跟在段落后的表格（无空行）会渲染为纯文本。
    result = ensureBlankLineBeforeGfmTables(result)

    // #312② 数学块降级（方案 C）：成对数学定界符（$$...$$ / \(...\) / \[...\]）
    // → tex 围栏/行内代码（见 transformMathFallback KDoc）。
    return transformMathFallback(result)
}

/**
 * 确保 GFM 表格前有一个空行。
 *
 * JetBrains markdown 解析器仅在块边界处检测 GFM 表格。
 * 当 LLM 在段落之后直接输出表格（无空行）时，
 * `|` 字符会被当作字面文本，表格无法渲染。
 *
 * 模式：非表格行 \n |表头| \n |---| → 非表格行 \n\n |表头| \n |---|
 *
 * #471③ 围栏意识（spec §3.3 矩阵修订③）：栏内的表格形态是代码字面——
 * 插入空行会改写代码块内容（完结渲染正确性缺陷），流式下更会改写 gate
 * 已放行的栏内前缀（性质测试 random 轮 k=318 实证：未闭合围栏行内
 * 「文字行\n|表头|\n|---|」被插空行）。行级围栏跟踪与
 * [normalizeTaskListMarkers]/[transformMathFallback] 同模式：只对栏外
 * 文本区跑正则，栏内行原样。
 */
internal fun ensureBlankLineBeforeGfmTables(text: String): String {
    // 2026-08-26 流式卡顿根因修复（simpleperf 实证 ICU RegexMatcher 占主线程
    // CPU 8.35% 全进程第一）：该正则对全文扫描，流式期间每批（100ms）全量重跑。
    // 模式必然含 '|'（组 2/3 的表格行）——无 '|' 的文本（essay/纯段落常态）
    // 不可能命中，native contains 扫描短路，正则零成本。
    if (!text.contains('|')) return text
    val lines = text.split("\n")
    val chunks = ArrayList<CharSequence>(lines.size)
    val run = StringBuilder() // 当前栏外文本区（行粒度，待表格正则）
    // 2026-09-30 坍缩重建根修：行哨兵改显式计数——原 run.isNotEmpty() 把
    // 「首行为空行」（闭合围栏后空行 append 后 run 仍 == ""）误判为 run 未
    // 启动，下一行跳过分隔换行符 → 空行被静默吞噬；流式中 | 首次到达时该
    // 吞噬首次生效 → 已放行前缀中段非前缀改写 → pilot RESETKEY 重建坍缩
    //（真机三案 13:55/14:13/14:40 定罪，divergeAt 全落围栏闭合后空行处）。
    var runLines = 0
    var fenceMarker: Char? = null
    var minFenceLen = 0
    for (line in lines) {
        // #471③：围栏判定统一至 MarkdownFenceLine（与三变换/gate 同语义）
        val openFence = MarkdownFenceLine.open(line)
        when {
            fenceMarker != null -> {
                // 栏内（含闭合围栏行）原样；仅同字符且足够长的无 info 围栏行能闭合
                if (MarkdownFenceLine.closes(line, fenceMarker!!, minFenceLen)) {
                    fenceMarker = null
                    minFenceLen = 0
                }
                if (runLines > 0) {
                    chunks.add(insertTableBlankLinesIn(run.toString()))
                    run.setLength(0)
                    runLines = 0
                }
                chunks.add(line)
            }
            openFence != null -> {
                // 开启围栏：先冲刷栏外区，围栏行本身原样
                if (runLines > 0) {
                    chunks.add(insertTableBlankLinesIn(run.toString()))
                    run.setLength(0)
                    runLines = 0
                }
                chunks.add(line)
                fenceMarker = openFence.first
                minFenceLen = openFence.second
            }
            else -> {
                if (runLines > 0) run.append('\n')
                run.append(line)
                runLines++
            }
        }
    }
    if (runLines > 0) chunks.add(insertTableBlankLinesIn(run.toString()))
    return chunks.joinToString("\n")
}

/** 栏外文本区的表格前空行插入（[TABLE_AFTER_TEXT_REGEX] 的局部应用）。 */
private fun insertTableBlankLinesIn(region: String): String =
    region.replace(TABLE_AFTER_TEXT_REGEX) { m ->
        "${m.groupValues[1]}\n\n${m.groupValues[2]}\n${m.groupValues[3]}"
    }

/**
 * 渲染归一化（2026-08-13 提取；#471③ 与流式同源重构）：与 MarkdownContent
 * 渲染完全一致的文本预处理——预解析（parseMarkdownFlow）必须用同一归一化
 * 结果，否则解析出的 AST 与实际渲染内容不一致（换行差异 → 高度不同
 * ——实测 214 vs 331）。
 *
 * 不变量（NormalizeSentinelEquivalenceTest 钉死）：
 * normalizeForRender(raw, isUser=false) == normalizeForStreaming(raw) 逐字节。
 */
internal fun normalizeForRender(raw: String, isUser: Boolean): String {
    val marked = normalizeTaskListMarkers(normalizeMarkdownCore(raw))
    val withUser = if (!isUser) marked else
        // 用户消息：单个 \n 在 Markdown 中不换行（软换行）。置于 task 标记
        // 之后：两者皆行锚定操作、可交换（混合 fixture 等价测试）；多行
        // 公式块的行结构已在共享核心成围栏、不被打散（#312② 原注释迁移）。
        marked.replace(SINGLE_NEWLINE_REGEX, "\n\n")
    return splitOversizedParagraphsByPosition(withUser)
}

/**
 * 流式 ingest 归一化（#471③，pilot 专用，spec §3.1）：与完结渲染同一核心
 * 同一序——流式显示的文本与完结渲染的文本逐字节一致（终帧=流式帧），
 * 完结换装从「文本不同→排版重排→跳变」变为「同文本换渲染器→视觉无事
 * 发生」。放行单调性（已放行前缀不被回改）的逐变换证明见 spec §3.3；
 * 性质测试 NormalizationStreamingMonotonicityTest 逐字符增长模拟钉死。
 */
internal fun normalizeForStreaming(raw: String): String =
    splitOversizedParagraphsByPosition(normalizeTaskListMarkers(normalizeMarkdownCore(raw)))

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
 * 超长段落空行化（位置制，#471③ 语义重定义，spec §3.2）：连续普通文本行
 * run 内，行 j 之后的边界升级为空行 ⟺ cumEnd(j) ≥
 * [SPLIT_PARAGRAPH_THRESHOLD_CHARS]（cumEnd(j) = run 起点到行 j 换行含的
 * 累计字符）。
 *
 * 与旧全段判定（run 总字符 ≥3000 时全 run 空行化）的差异：3000 字以内的
 * 头部边界保持单换行、越过 3000 的边界起才升级——效果上 >3000 段落呈现
 * 「头部一块 + 尾部逐行成块」的稳定接缝。拆分目的（MarkdownChunking
 * 分片）只需尾部可拆；接缝在流式/完结两侧一致出现 = 不产生跳变
 * （一致性优先于均匀性）。
 *
 * 流式单调性（放行不回改的关键，spec §3.3 末行）：行 j 分类
 * （isPlainParagraphLine 全部 startsWith 判定）与 cumEnd(j) 在行 j 完成
 * 时刻即固定 → 边界升级决策单调不翻转 → 对任意截断前缀 S[:k]，本函数
 * 输出是全量输出 split(S) 的前缀（NormalizationStreamingMonotonicityTest
 * 性质钉死）。判定绝不等待下一行存在才做——那会把已放行的换行回改成
 * 空行（非前缀）。
 */
internal fun splitOversizedParagraphsByPosition(text: String): String {
    if (text.length < SPLIT_PARAGRAPH_THRESHOLD_CHARS) return text
    val lines = text.split("\n")
    val out = StringBuilder(text.length + lines.size)
    var inRun = false        // 当前普通行 run 开放中
    var cumEnd = 0           // run 起点到上一完成行换行含的累计字符
    var inFence = false
    var i = 0
    while (i <= lines.size) {
        val line = if (i < lines.size) lines[i] else ""
        val isFence = line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")
        // 候选段成员条件：非围栏内 / 非围栏边界 / 普通行
        val plain = !inFence && i < lines.size && !isFence && isPlainParagraphLine(line)
        if (plain) {
            // 边界决策（行 i 完成时刻即定案）：run 已开放且到上一行末的
            // 累计 ≥ 阈值 → 行 i-1 行尾换行已写，补一个 \n 成空行（升级）
            if (inRun && cumEnd >= SPLIT_PARAGRAPH_THRESHOLD_CHARS) out.append('\n')
            if (!inRun) { inRun = true; cumEnd = 0 }
            out.append(line)
            if (i < lines.size - 1) out.append('\n')
            cumEnd += line.length + 1
            i++
            continue
        }
        // 非普通行 / 围栏边界 / 空行：关闭 run（决策即时，无冲刷缓冲）
        inRun = false
        cumEnd = 0
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
    // rememberAsyncMarkdownState）——流式内容必须 false（批处理 cadence +
    // conflate 铁律路径，rememberMarkdownState 保留）。
    asyncParse: Boolean = false,
    // #442 R2 分片唤醒（A2）：注册在案的流式大文本 part 的分片控制器
    //（PartContent 按 part.id 从 broker 查得；null=原路径零改造）。
    shardCtl: ShardController? = null,
    // #517（2026-10-05）：终末块距载体修剪——内容末块恰为代码/公式块（闭栏
    // 行结尾）且处于 turn 终态（liveText==null / 静态渲染 / 末片）时置 true：
    // 块 bottom 外距归零，内容→统计栏间隙与 user 侧（4dp+行内）对齐。块间
    // 分离不受影响（top 外距保留，非末块 bottom 保留）。
    trimTrailingBlockGap: Boolean = false,
    // #518①（2026-10-05）：内容终态旗标——true 时 pilot gate 拒绝即全量放行
    //（完结后无后续增量，扣留=永扣）。由 PartContent 从 bus 覆盖生命周期与
    // 会话流式态推导，仅 pilot 分支消费。
    contentTerminal: Boolean = false,
) {
    // #517 GapDiag：入口旗标取证（DEBUG-only 探针）
    if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
        android.util.Log.w(
            "GapDiag",
            "MC len=" + markdown.length +
                " trim=" + trimTrailingBlockGap +
                " preParsed=" + (preParsedState != null) +
                " override=" + (overrideState != null) +
                " async=" + asyncParse,
        )
    }
    //（customFontSize/immediate 两死参数已随 2026-10-03 清理批次退役——
    // 排版/密度由 LocalChatDensity 驱动，解析策略由分支自身决定。）
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

    // #488②：代码高亮主题（M3 令牌 → highlights SyntaxTheme 9 角色，
    // CodeSyntaxTheme.kt）。键 = 参与映射的 colorScheme 角色（下方 components
    // 键纪律同款）——主题/动态色/AMOLED 切换 → 新实例 → components 键变化
    // → codeFence 闭包重建，防代码块残留旧主题色。
    val colorScheme = MaterialTheme.colorScheme
    val codeSyntaxTheme = remember(
        colorScheme.primary, colorScheme.secondary, colorScheme.tertiary,
        colorScheme.onSurface, colorScheme.onSurfaceVariant,
    ) { colorScheme.toCodeSyntaxTheme() }

    // components 闭包捕获 linkColor/typography/textColor + #488① custom 钩子捕获
    // codeBlockBg/codeBlockFg/typography.code + #488② codeFence/codeBlock 捕获
    // codeSyntaxTheme。键必须包含它们：主题切换时颜色变化
    // → 重建闭包 → 内部 AnnotatedString 用新颜色重建，否则切换主题后文字颜色停留
    // 在旧主题（暗色浅色在亮色背景下"过曝"）。
    val components = remember(density, isUser, linkListener, linkColor, textColor, codeBlockBg, codeBlockFg, typography.code, codeSyntaxTheme, trimTrailingBlockGap) {
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
                // #437 崩溃修复：ATX 提取本身走 getTextInNode——流式 snapshot
                // 失配帧（AST 非空 + content 空）会越界，统一走安全辅助。
                val h1Text = remember(model.content, model.node) {
                    safeHeadingText(model.content, model.node)
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
            // #437 崩溃修复：heading2-6 原走库默认组件（无越界兜底）——真机
            // 13:59 崩溃栈定罪（MarkdownHeader→buildMarkdownAnnotatedString→
            // getTextInNode, AST[3,29] vs content len0：流式 snapshot 失配帧）。
            // 同款兜底覆盖（heading1 同构 + #432 buildClickableMarkdown 内越界
            // 降级），ATX_CONTENT 提取走安全辅助。
            heading2 = { model -> SafeHeading(model, typography.h2, linkListener, linkColor, uriHandler) },
            heading3 = { model -> SafeHeading(model, typography.h3, linkListener, linkColor, uriHandler) },
            heading4 = { model -> SafeHeading(model, typography.h4, linkListener, linkColor, uriHandler) },
            heading5 = { model -> SafeHeading(model, typography.h5, linkListener, linkColor, uriHandler) },
            heading6 = { model -> SafeHeading(model, typography.h6, linkListener, linkColor, uriHandler) },
            // #471④-b：任务列表复选框——m3 Material Checkbox（官方 demo 同款装配）。
            // 此前未覆写 → 基础模块默认 checkedIndicator 渲染字面 "[x] "/"[ ] " 等宽
            // 文本（用户验收否决形态：要求真 markdown 复选框而非文字）。
            checkbox = { model ->
                com.mikepenz.markdown.m3.elements.MarkdownCheckBox(
                    content = model.content,
                    node = model.node,
                    style = model.typography.text,
                )
            },
            // #488②：代码块语法高亮——自建壳（fork -code v0.45.0，
            // HighlightedCode.kt）。components 单例覆写 → 八个 MarkdownContent
            // 调用面（assistant 双路径/思考/工具卡×2/通知卡/压缩卡/预览）自动
            // 获得；user 气泡结构性不触达（PartContent isUser 分支走纯 Text）。
            // 未知语言引擎侧静默纯色 = 现状等价；流式期 produceState 按 code
            // 批重启（批节奏天然节流），初值纯文本无空窗。
            codeFence = { model ->
                SafeHighlightedCodeFence(
                    content = model.content,
                    node = model.node,
                    style = model.typography.code,
                    theme = codeSyntaxTheme,
                    trimBottomGap = trimTrailingBlockGap,
                )
            },
            codeBlock = { model ->
                SafeHighlightedCodeBlock(
                    content = model.content,
                    node = model.node,
                    style = model.typography.code,
                    theme = codeSyntaxTheme,
                    trimBottomGap = trimTrailingBlockGap,
                )
            },
            // #488①：块内 HTML——库对 HTML_BLOCK 零组件（v0.45.0 base+m3 AAR 二进制
            // grep 实证），custom 默认空 lambda = 整块隐形（内容静默丢失）。覆写：
            // 等宽代码样式呈现原文（与 code fence 同视觉域），内容可见不丢。
            // 真 HTML 渲染（WebView 级）属 #488 远期；整消息 HTML 走
            // looksLikeHtmlPayload 预览通道不受此影响（混排块才进这里）。
            custom = { elementType, model ->
                if (elementType == org.intellij.markdown.MarkdownElementTypes.HTML_BLOCK) {
                    HtmlBlockRaw(
                        content = model.content,
                        node = model.node,
                        style = typography.code,
                        background = codeBlockBg,
                        foreground = codeBlockFg,
                    )
                }
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
        // #477 探针：分支取证（DEBUG-only）
        if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
            android.util.Log.w("A11yDiag", "path=preParsed chunked=" + (blockRange != null) +
                " len=" + markdown.length + " stateType=" + preParsedState.javaClass.simpleName)
        }
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
                // #517②：全量静态路径专用 success 槽——跳过尾随空白子节点
                //（内容结尾 \n 解析出尾随 EOL，mikepenz MarkdownSuccess 会在其
                // 前垫 Spacer(padding.block)，终末块→统计栏间隙凭空多出一段；
                // chunk 路径的计划态 AST 天然无尾随 EOL，两侧由此不一致）。
                success = terminalSuccessSlot(),
            )
        }
        return
    }

    // #265 P0-a 试点分支（spec §4）：开关开启、非用户消息、无外部覆写态且
    // 非 asyncParse 时，流式渲染走 StreamingMarkdownState 前缀差分 append。
    // #471③ 归一化前移（终帧=流式帧）：pilot 内部先归一化（与完结渲染同源
    // 逐字节一致）再前缀差分——流中即见最终形态（tex 围栏/真复选框），
    // 完结换装无归一化重排跳变。
    // 回退 = flavor 的 STREAMING_MD_PILOT 置 false。
    // #461：准入收为 streamingPilotEligible 纯函数——静态文本(asyncParse=true)
    // 不得误入(空 state 靠逐帧 append 填充,ε 窗竞态 → H=0 僵尸展开态)。
    //（#472/#504 完结换装桥接机制已随「pilot 即终态」退役 2026-10-03——
    // 见下方 pilotRetained 注释；本注释块保留流式准入语义。）
    // #442 A2：已分片（broker 有发布）的 part 完结后**保持 pilot**（终帧=终态，
    // #471③ 归一化同源）——完结切全量终态会与冻结 shard items 双渲染（内容
    // 重复）；async 终态预热也一并跳过（无用功）。
    val shardHold = shardCtl != null && shardCtl.hasPublished()
    // #509 方案B 二期（2026-10-03 用户裁决）：**pilot 即终态**——shardHold 的
    // 「完结不切渲染器」语义泛化到一切幸存 pilot 槽（pilotEverRendered 槽位
    // 记忆，#509 原地换名后跨毕业存活）。毕业/完结（asyncParse 翻转）不再切换
    // 终态渲染器：内容全同时零增量（权威转写=流式帧逐字节），内容真变时走
    // pilot 原生前缀差分/非前缀宽限重建——两层补偿（#504 换装指纹桥 + #472
    // async 保持窗）随之全族退役。节点滚出视口销毁后冷组合走下方终态路径
    // （pilotEverRendered=false 天然回冷）。
    //（2026-09-28 #472 收窄的「pilot 永不退场 → 清空+回灌闪烁」定罪在此解除：
    // 该症根因是归一化坐标错位使完结全文对 pilot 恒非前缀——#471③ 前移后
    // 终帧=流式帧，完结内容前缀一致（#509 方案B 真机两轮表格 hold 帧零 delta
    // 实证）。）
    var pilotEverRendered by remember { androidx.compose.runtime.mutableStateOf(false) }
    val pilotRetained = StreamingMarkdownPilot.enabled && pilotEverRendered
    if (streamingPilotEligible(overrideState != null, asyncParse, isUser) && StreamingMarkdownPilot.enabled ||
        pilotRetained ||
        shardHold
    ) {
        // #437：pilotState.state 只收 SafePrefixGate 放行的定案内容；扣留尾部
        // 经毕业/EOF flush 释放（降亮区已退役）。回退 = STABLE_REVEAL_PILOT
        // 置 false（gate 旁路，pilot 原行为）。
        pilotEverRendered = true
        // freeze 恒 false：#472 async 桥接窗已退役（无切换帧可桥），shard 冷续
        // 的 EOF flush 亦须放行——冻结语义全消。
        val pilotState = rememberPilotStreamingMarkdownState(
            markdown,
            shard = shardCtl,
            // #518①：终态全量揭示旗标（来源见参数声明处注释）
            terminal = contentTerminal,
        )
        androidx.compose.foundation.layout.Column {
            // #437 崩溃修复：非前缀重建（resetKey++）换 state 实例的同一帧，
            // 库 Markdown 内部 collectAsState 对流实例的记忆可能残留旧 snapshot
            // （旧 AST）与新实例空 content 组成失配帧（getTextInNode 越界）。
            // key(state) 强制实例变化时整个子树重建——失配帧从构造上消失。
            androidx.compose.runtime.key(pilotState.state) {
            Markdown(
                streamingMarkdownState = pilotState.state,
                colors = colors,
                typography = typography,
                components = components,
                padding = padding,
                animations = animations,
                imageTransformer = Coil3ImageTransformerImpl,
                modifier = Modifier.fillMaxWidth(),
            )
            }
        }
        // #477 探针：分支取证（DEBUG-only；retained=完结保持（pilot 即终态））
        if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
            android.util.Log.w("A11yDiag", "path=pilot retained=" + pilotRetained +
                " len=" + markdown.length)
        }
        return
    }

    // 2026-08-22：非流式长文本 fallback 异步化——库的 rememberMarkdownState
    // 在主线程同步 parseBlocking（字节码实证 parse$2 内联 parseBlocking，无
    // flowOn）；预解析 miss 时冷态快滑巨帧 84ms（framestats vsync→input）。
    // asyncParse=true 时归一化+解析全程 Default 线程，主线程仅收 StateFlow 发射。
    //
    // #428（2026-09-23 根因修复）：异步路径首组合帧恒 State.Loading 占位——
    // 大卡展开把恢复位邻域条目逐出组合窗后，收起闭合帧原子重组时小文本
    // （<preParsed 门槛 200 字符,不查渲染供给 registry）以 Loading 短高入测
    // （真机 #s1 条目 199px），Default 线程解析完成于下一帧回填真高（467px）
    // → 其下内容 +268px 二次重排=「收起末尾上推然后突然高度复位」主诉。
    // 小文本（≤[ASYNC_PARSE_MIN_CHARS]）回归库同步解析：parseBlocking 于
    // remember 内联执行（1-3ms 有界,无跨线程等待=非 runBlocking 家族）,
    // 首测即终高,占位帧从构造上消失;大文本保持异步（84ms 冷滑巨帧防线,
    // 且 ≥200 字符有 registry 预解析覆盖）。
    //（此路径仅在 pilot 未保留时到达：冷重入/跳转/视口回收的全新节点——
    // #509 后幸存节点毕业不落此（pilot 即终态）。）
    val asyncTerminal: com.mikepenz.markdown.model.MarkdownState? =
        if (overrideState == null && asyncParse && !shardHold &&
            markdown.length > ASYNC_PARSE_MIN_CHARS
        ) {
            rememberAsyncMarkdownState(markdown, isUser)
        } else {
            null
        }
    val markdownState = overrideState ?: asyncTerminal ?: if (asyncParse) {
        rememberSyncMarkdownState(markdown, isUser)
    } else {
        // 流式/同步路径：归一化保留在此分支（流式单条增量成本可控）
        val normalizedForLib = remember(markdown, isUser) { normalizeForRender(markdown, isUser) }
        rememberMarkdownState(
            content = normalizedForLib,
            retainState = true,
        )
    }

    // #477 探针：分支取证（DEBUG-only）
    if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
        val src = when {
            overrideState != null -> "override"
            asyncTerminal != null -> "asyncTerminal"
            asyncParse -> "syncSmall"
            else -> "libStreaming"
        }
        // stateType 字段已移除（组合期 StateFlow.value 读触发 lint 门禁——#477 诊断探针保留 src/len；v0.4.0-beta CI 定罪）
        android.util.Log.w("A11yDiag", "path=render src=" + src + " len=" + markdown.length)
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
        // #517②：静态/异步路径同款终态槽（尾随空白子节点跳过，见 preParsed 分支注）
        success = terminalSuccessSlot(),
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

    /** 当前状态(终态读取:#428 缓存入账)。 */
    fun currentState(): State = _state.value

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

/** #428:异步解析的最小文本长度(字符)——短于此走同步解析,首组合即终高。 */
private const val ASYNC_PARSE_MIN_CHARS = 2048

/**
 * #428 同族加固:跨组合解析终态缓存(有界 LRU,线程安全,JVM 可单测)。
 *
 * 动机:>[ASYNC_PARSE_MIN_CHARS] 的文本 part 走 [rememberAsyncMarkdownState],
 * 首组合帧恒 `State.Loading` 占位。渲染供给 registry 的 Parsed 条目会被
 * 视口离场 remove(RenderReadiness D-7 语义)且 <200 字符不查 registry——
 * 大卡收起闭合帧原子重组恢复位邻域条目时,任何 miss 都让条目以 Loading
 * 短高入测、解析回填帧二次重排(真机 #s1 199→467,+268px 跳变同族)。
 * 本缓存以内容为键保留解析终态:同内容跨组合(收起闭合帧/滚出滚回)命中
 * 即同步终态,首测即终高。上界 [MAX_ENTRIES] 条 × 大文本(~20KB)≈ 0.7MB
 * 内存换零占位帧;AST 不可变,跨 Markdown() 实例共享安全。
 */
internal object MarkdownParsedStateCache {
    internal const val MAX_ENTRIES = 32

    private val lock = Any()
    private val map = object : LinkedHashMap<String, State>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, State>): Boolean =
            size > MAX_ENTRIES
    }

    /** 命中返回解析终态;miss 返回 null。 */
    fun get(content: String): State? = synchronized(lock) { map[content] }

    /** 终态入缓存(Loading 不入——非终态占位无复用价值)。 */
    fun put(content: String, state: State) {
        if (state is State.Loading) return
        synchronized(lock) { map[content] = state }
    }

    fun size(): Int = synchronized(lock) { map.size }

    /** 仅测试用。 */
    fun clearForTest() = synchronized(lock) { map.clear() }
}

/**
 * #428:同步解析的 MarkdownState——remember 时内联 parse 出终态,无 Loading 帧。
 *
 * 库的 [com.mikepenz.markdown.model.parseMarkdown] 为非 suspend 纯函数入口
 * (与 parseMarkdownFlow 的终态发射同源);调用发生在 remember 计算内
 * (组合线程内联 CPU 计算,≤2KB 有界),**不是** runBlocking 等待后台流的
 * 禁用家族(2026-09-23 ANR 教训仅针对跨线程阻塞等待)。
 * [parse] 防御实现直接返回已持有终态(Markdown 可组合项按 0.43 字节码核对
 * 不调用它;0.45 语义兼容)。
 */
private class SyncMarkdownState(parsed: State) : MarkdownState {
    private val _state = MutableStateFlow<State>(parsed)
    override val state: StateFlow<State> = _state.asStateFlow()
    private val _links = MutableStateFlow<Map<String, String>>(emptyMap())
    override val links: StateFlow<Map<String, String>> = _links.asStateFlow()
    override suspend fun parse(): State = _state.value
}

@Composable
private fun rememberSyncMarkdownState(content: String, isUser: Boolean): MarkdownState =
    remember(content, isUser) {
        SyncMarkdownState(
            parseMarkdown(normalizeForRender(content, isUser)),
        )
    }

/**
 * #442 R2 分片唤醒（A2）：流式冻结块渲染——归一化切片（pilot 归一化坐标，
 * 已是终态形态，#471③ 同源）同步解析 + preParsed 通道（无 Loading 空窗：
 * 换装帧首组合即全高）。冻结内容不可变 → remember(text) 单次解析；item
 * 回收重组合按 text 重解析（A2 接受；后续可接 SyncParseCache）。
 */
@Composable
internal fun StreamShardContent(markdown: String, textColor: Color, trimTrailingBlockGap: Boolean = false) {
    val parsed = remember(markdown) { parseMarkdown(markdown) }
    // [507-shard] #507 消失取证：冻结块组合事实（文本量）+ 实测高度
    if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
        android.util.Log.w("507-shard", "shardContent len=" + markdown.length)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { size ->
                if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                    android.util.Log.w("507-shard", "shardContent h=" + size.height + "px len=" + markdown.length)
                }
            }
    ) {
        MarkdownContent(
            markdown = "",
            textColor = textColor,
            isUser = false,
            preParsedState = parsed,
            trimTrailingBlockGap = trimTrailingBlockGap,
        )
    }
}

/**
 * #517：终末块距载体判定——内容（trimEnd 后）最后一非空行恰为闭合围栏
 * （≤3 空格缩进 + ≥3 个 ` 或 ~ 且标记后仅空白）= 终末块是代码/公式块，
 * 其 bottom 外距在 turn 终态下属超额（内容→统计栏间隙统一到 user 侧）。
 * 纯函数，调用方：PartContent（liveText==null 终态）/ MessageCardAssistant
 * 末 chunk / ChatMessageList 末 #g 冻结片。
 */
internal fun endsWithBlockGapCarrier(content: String): Boolean {
    val trimmed = content.trimEnd()
    if (trimmed.isEmpty()) return false
    val lastLine = trimmed.substring(trimmed.lastIndexOf('\n') + 1)
    val marker = lastLine.trimStart(' ', '\t')
    if (marker.isEmpty()) return false
    val c = marker[0]
    if (c != '`' && c != '~') return false
    var n = 0
    while (n < marker.length && marker[n] == c) n++
    return n >= 3 && marker.drop(n).isBlank()
}

@Composable
private fun rememberAsyncMarkdownState(content: String, isUser: Boolean): MarkdownState {
    // #428 同族加固:缓存命中→同步终态,跨组合首测即终高(registry 逐出/
    // 未注册场景闭合帧零占位)。
    MarkdownParsedStateCache.get(content)?.let { cached ->
        return remember(content) { SyncMarkdownState(cached) }
    }
    val impl = remember { AsyncMarkdownStateImpl() }
    LaunchedEffect(impl, content, isUser) {
        impl.parseAsync(content, isUser)
        // 终态入缓存:同内容下一次跨组合(收起闭合帧重入等)命中即零占位。
        MarkdownParsedStateCache.put(content, impl.currentState())
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
/**
 * #517②：全量静态渲染（preParsed 非分片 / asyncTerminal / syncSmall / override）
 * 的 success 槽——复刻 mikepenz [MarkdownSuccess] 的 Column+MarkdownElement 结构，
 * 唯一差异：**跳过尾随空白子节点**。
 *
 * 根因：内容结尾的 `\n` 会被解析为尾随 EOL 顶层子节点，而 MarkdownElement 的
 * includeSpacer 机制在**每个**子节点（含空白节点）前垫 `Spacer(padding.block)`
 * ——终末块与统计栏之间凭空多出该间隔；分片路径的 chunk 计划态 AST 天然不含
 * 尾随 EOL（其内容在计划构建期已裁尾），同一消息两侧路径间隙不一致即源于此。
 * 尾随空白节点无可见内容，跳过仅消除尾部多余间隔，块间视觉零变化。
 */
internal fun terminalSuccessSlot():
    @Composable (State.Success, com.mikepenz.markdown.compose.components.MarkdownComponents, Modifier) -> Unit =
    { st, comps, mod ->
        androidx.compose.foundation.layout.Column(mod) {
            val kids = st.node.children
            var last = kids.size
            while (last > 0) {
                val n = kids[last - 1]
                val s0 = n.startOffset.coerceIn(0, st.content.length)
                val e0 = n.endOffset.coerceIn(0, st.content.length)
                if (e0 > s0 && !st.content.substring(s0, e0).isBlank()) break
                last--
            }
            if (dev.leonardo.ocbeacon.BuildConfig.DEBUG && last < kids.size) {
                android.util.Log.w(
                    "GapDiag",
                    "terminalSlot skipped " + (kids.size - last) + " trailing blank kids=" + kids.size + " contentLen=" + st.content.length,
                )
            }
            for (i in 0 until last) {
                com.mikepenz.markdown.compose.MarkdownElement(kids[i], comps, st.content)
            }
        }
    }

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

/**
 * #437 崩溃修复：流式 snapshot 失配帧（AST 与 content 不同源的一瞬，真机
 * 13:59 定罪 AST[3,29] vs content len0）中标题文本的安全提取——越界预检 +
 * runCatching 双保险，失配帧降级空串一帧，状态收敛后 remember 键变化恢复。
 */
private fun safeHeadingText(content: String, node: ASTNode): String = runCatching {
    val atx = node.children.firstOrNull { it.type == MarkdownTokenTypes.ATX_CONTENT } ?: node
    if (atx.startOffset > content.length || atx.endOffset > content.length) return ""
    atx.getUnescapedTextInNode(content).toString().trim().trimStart('#').trim()
}.getOrDefault("")

/** #437：heading2-6 兜底组件（#432 语义推广——buildClickableMarkdown 内越界降级 + ATX 安全提取）。 */
@Composable
private fun SafeHeading(
    model: com.mikepenz.markdown.compose.components.MarkdownComponentModel,
    style: TextStyle,
    linkListener: LinkInteractionListener,
    linkColor: Color,
    uriHandler: UriHandler,
) {
    val settings = annotatorSettings(linkInteractionListener = linkListener)
    val result = remember(model.content, model.node, style.color, linkColor) {
        buildClickableMarkdown(
            content = model.content,
            node = model.node,
            style = style,
            annotatorSettings = settings,
            linkColor = linkColor,
        )
    }
    val fallbackText = remember(model.content, model.node) { safeHeadingText(model.content, model.node) }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    MarkdownBasicText(
        text = if (result.annotatedString.isNotBlank()) result.annotatedString else AnnotatedString(fallbackText),
        style = style,
        onTextLayout = { layoutResult = it },
        modifier = Modifier.clickableMarkdown(
            result = result,
            layoutResultProvider = { layoutResult },
            uriHandler = uriHandler,
        ),
    )
}

/**
 * #488①：块内 HTML 原文呈现（custom 钩子 HTML_BLOCK 分支）。
 *
 * 区间截取走 runCatching + 边界 clamp（#437 崩溃先例：流式 snapshot 失配帧
 * node 区间可越界 content 长度）；截取失败回退整 content（宁可多显不丢内容）。
 */
@Composable
private fun HtmlBlockRaw(
    content: String,
    node: ASTNode,
    style: TextStyle,
    background: Color,
    foreground: Color,
) {
    val raw = remember(content, node) {
        runCatching {
            val s = node.startOffset.coerceIn(0, content.length)
            val e = node.endOffset.coerceIn(s, content.length)
            content.subSequence(s, e).toString().trimEnd()
        }.getOrDefault(content)
    }
    val scroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = background, shape = RoundedCornerShape(6.dp))
            .horizontalScroll(scroll),
    ) {
        Text(
            text = raw,
            style = style.copy(color = foreground, fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(SpacingTokens.MD.dp),
        )
    }
}
