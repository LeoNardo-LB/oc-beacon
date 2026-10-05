package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.CodeHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.intellij.markdown.ast.ASTNode

/*
 * #488②：主对话流代码块（围栏/缩进）语法高亮组件。
 * fork 自 mikepenz multiplatform-markdown-renderer-code v0.45.0 的
 * MarkdownHighlightedCode.kt（@ tag v0.45.0）——不引 -code 依赖的仓库内
 * 自建壳（影响面分析 §1.0：本需求的三处定制全落其 private 区）。
 *
 * 与上游 fork 基线的差异（core 升级时 diff 官方同文件）：
 *  ① 主题注入 = [SyntaxTheme]（M3 令牌映射，CodeSyntaxTheme.kt）而非
 *     SyntaxThemes.default，且纳入 produceState 键——上游只键 code，
 *     切主题后旧值残留（颜色 stale）。
 *  ② 每高亮作业新建 Highlights.Builder——上游共享 builder 是可变对象
 *     （字节码含 setCode/setLanguage setter），同消息多代码块并发高亮
 *     共享实例会竞态。
 *  ③ 区间守卫：PhraseLocation.end 为 exclusive 语义（官方 README
 *     emphasis(13,25) = 索引 13..24）；反向区间野外真实存在
 *     （SnipMeDev/Highlights#75）→ addStyle 前守卫，防 Reversed range
 *     崩溃（mikepenz#415 同类）。FileViewer HighlightBuilder 的 end+1
 *     是多染一字符的存量偏差（#489），此处按 exclusive 正确语义写。
 *  ④ AppLogger 打点（DEBUG-only，组合侧）。
 * showHeader/immediate 参数未带入（批 1 不开——纯着色零文案零 i18n 面）。
 *
 * #488③：SafeHighlightedMathBlock——数学降级块（```math 围栏，
 * [transformMathFallback] 产物专属识别位）的「公式」徽标 + 手写轻着色。
 * highlights 引擎无 tex/math 语言（SyntaxLanguage 枚举取证），
 * 不走 [buildSafeHighlightedAnnotatedString]。
 */

/** #488③：数学降级块的围栏 info 识别位（transformMathFallback 产物专属，AI 手写 math 围栏同享受徽标——语义本就是数学）。 */
internal const val MATH_FENCE_LANGUAGE = "math"

@Composable
internal fun SafeHighlightedCodeFence(
    content: String,
    node: ASTNode,
    style: TextStyle,
    theme: SyntaxTheme,
    /** #517：终末块距载体修剪——true 时外距 bottom 归零（内容→统计栏间隙与 user 侧对齐）。 */
    trimBottomGap: Boolean = false,
) {
    // #517 GapDiag：components 闭包捕获值取证（DEBUG-only 探针）
    if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
        AppLogger.d("GapDiag", "FENCE trim=" + trimBottomGap + " contentLen=" + content.length)
    }
    MarkdownCodeFence(content, node, style) { code, language, codeStyle ->
        if (language.equals(MATH_FENCE_LANGUAGE, ignoreCase = true)) {
            SafeHighlightedMathBlock(code = code, style = codeStyle, trimBottomGap = trimBottomGap)
        } else {
            SafeHighlightedCode(code = code, language = language, style = codeStyle, theme = theme, trimBottomGap = trimBottomGap)
        }
    }
}

@Composable
internal fun SafeHighlightedCodeBlock(
    content: String,
    node: ASTNode,
    style: TextStyle,
    theme: SyntaxTheme,
    /** #517 同 [SafeHighlightedCodeFence]。 */
    trimBottomGap: Boolean = false,
) {
    MarkdownCodeBlock(content, node, style) { code, language, codeStyle ->
        SafeHighlightedCode(code = code, language = language, style = codeStyle, theme = theme, trimBottomGap = trimBottomGap)
    }
}

@Composable
internal fun SafeHighlightedCode(
    code: String,
    language: String?,
    style: TextStyle,
    theme: SyntaxTheme,
    /** #517 同 [SafeHighlightedCodeFence]。 */
    trimBottomGap: Boolean = false,
) {
    // #517 GapDiag：叶子旗标取证（DEBUG-only 探针）
    if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
        AppLogger.d("GapDiag", "SHC lang=" + (language ?: "-") + " len=" + code.length + " trim=" + trimBottomGap)
    }
    val backgroundCodeColor = LocalMarkdownColors.current.codeBackground
    val codeBackgroundCornerSize = LocalMarkdownDimens.current.codeBackgroundCornerSize
    val codeBlockPadding = LocalMarkdownPadding.current.codeBlock
    // 初值 = 纯文本（无空窗）；key 含 theme——主题切换重启作业换新色（上游
    // 潜在 stale 修复）。language 变化仅在流式围栏改写 info 行时可观测，一并入键。
    val codeHighlights: AnnotatedString by produceState(
        initialValue = AnnotatedString(text = code),
        key1 = code,
        key2 = language,
        key3 = theme,
    ) {
        val job = launch(Dispatchers.Default) {
            val t0 = if (BuildConfig.DEBUG) System.currentTimeMillis() else 0L
            value = buildSafeHighlightedAnnotatedString(code, language, theme)
            if (BuildConfig.DEBUG) {
                AppLogger.d(
                    "CodeHL",
                    "len=" + code.length + " lang=" + (language ?: "-") +
                        " spans=" + value.spanStyles.size + " ms=" + (System.currentTimeMillis() - t0),
                )
            }
        }
        awaitDispose { job.cancel() }
    }

    MarkdownCodeBackground(
        color = backgroundCodeColor,
        shape = RoundedCornerShape(codeBackgroundCornerSize),
        modifier = Modifier
            .fillMaxWidth()
            // #517：bottom 外距承担块间分离（top 不变）；终末块（turn 尾）
            // 修剪归零——内容→统计栏间隙与 user 侧 4dp+行内基准对齐
            .padding(
                top = SpacingTokens.SM.dp,
                bottom = if (trimBottomGap) 0.dp else SpacingTokens.SM.dp,
            ),
        language = language,
        code = code,
    ) {
        MarkdownBasicText(
            text = codeHighlights,
            style = style,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(codeBlockPadding),
        )
    }
}

/*
 * #488③（方案 c：降级 + 标注升级）：数学降级块渲染——
 * [transformMathFallback] 把完结消息的块级 $$…$$ / \[…\] 归一化为 ```math
 * 围栏，此处按 [MATH_FENCE_LANGUAGE] 精确识别（不误伤 AI 手写 tex 围栏——
 * 那仍走 [SafeHighlightedCode]，引擎无 tex 语言 → 纯文本等价）。
 *
 * 形态 = 代码块同款底壳（MarkdownCodeBackground）+「公式」徽标行
 * （i18n）+ 三角色轻着色等宽原文（真排版 KaTeX 级属远期）。
 * 数学变换只在完结渲染期发生（流式期无 math 围栏），静态内容同步构建 +
 * remember 即可——无 produceState 流式重启需求；键 = 参与着色的
 * colorScheme 角色（MarkdownContent 键纪律同款，主题切换重建换色）。
 */
@Composable
internal fun SafeHighlightedMathBlock(
    code: String,
    style: TextStyle,
    /** #517 同 [SafeHighlightedCode]。 */
    trimBottomGap: Boolean = false,
) {
    val backgroundCodeColor = LocalMarkdownColors.current.codeBackground
    val codeBackgroundCornerSize = LocalMarkdownDimens.current.codeBackgroundCornerSize
    val codeBlockPadding = LocalMarkdownPadding.current.codeBlock
    val colorScheme = MaterialTheme.colorScheme
    val mathText = remember(
        code,
        colorScheme.tertiary, colorScheme.secondary, colorScheme.onSurfaceVariant,
    ) {
        buildMathAnnotatedString(
            code = code,
            commandColor = colorScheme.tertiary,
            braceColor = colorScheme.onSurfaceVariant,
            scriptColor = colorScheme.secondary,
        )
    }

    MarkdownCodeBackground(
        color = backgroundCodeColor,
        shape = RoundedCornerShape(codeBackgroundCornerSize),
        modifier = Modifier
            .fillMaxWidth()
            // #517 同 SafeHighlightedCode：终末块 bottom 外距修剪
            .padding(
                top = SpacingTokens.SM.dp,
                bottom = if (trimBottomGap) 0.dp else SpacingTokens.SM.dp,
            ),
        language = MATH_FENCE_LANGUAGE,
        code = code,
    ) {
        Column(modifier = Modifier.padding(codeBlockPadding)) {
            MarkdownBasicText(
                // 库的 String 重载是 internal——公共重载只收 AnnotatedString
                text = AnnotatedString(stringResource(R.string.math_block_badge)),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.padding(bottom = SpacingTokens.XS.dp),
            )
            MarkdownBasicText(
                text = mathText,
                style = style,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

/**
 * 高亮构建（纯函数，JVM 可单测）。
 *
 * [SyntaxLanguage.getByName] 未命中返回 null（大小写不敏感；1.1.0 字节码
 * aconst_null 分支实证）——未知/空语言**纯文本直返**：实测引擎对无语言
 * 构建仍可能按 DEFAULT 语言集产出内容相关的噪声着色，无语言信息的高亮
 * 本身是猜测，静默纯色才是「与现状等价」的承诺行为。
 * 引擎异常/空产出降级纯文本（runCatching + orEmpty）——getHighlights()
 * 对无语言构建可返回 null（平台可空），高亮纯增益，任何失败不得影响
 * 代码块文本呈现。
 */
internal fun buildSafeHighlightedAnnotatedString(
    code: String,
    language: String?,
    theme: SyntaxTheme,
): AnnotatedString {
    val syntaxLanguage = language?.let { SyntaxLanguage.getByName(it) }
        ?: return AnnotatedString(code)
    val highlights = runCatching {
        Highlights.Builder()
            .code(code)
            .language(syntaxLanguage)
            .theme(theme)
            .build()
            .getHighlights()
    }.getOrNull().orEmpty()
    return applyHighlightSpans(code, highlights)
}

/**
 * span 应用（独立纯函数供 JVM 单测直测区间守卫）。
 *
 * 守卫三连：start 越界 / end 越界钳制 / 反向区间（end <= start）——全部
 * 跳过该 span；ColorHighlight 强制 alpha=1（上游同款，主题色不含透明度）。
 */
internal fun applyHighlightSpans(code: String, highlights: List<CodeHighlight>): AnnotatedString =
    buildAnnotatedString {
        append(code)
        val maxIndex = code.length
        highlights.forEach { h ->
            // end 为 exclusive 语义；coerceAtMost 防引擎产出越界区间
            val start = h.location.start
            val end = h.location.end.coerceAtMost(maxIndex)
            if (start < 0 || start >= maxIndex || end <= start) return@forEach
            when (h) {
                is ColorHighlight -> addStyle(
                    SpanStyle(color = Color(h.rgb).copy(alpha = 1f)),
                    start, end,
                )
                is BoldHighlight -> addStyle(
                    SpanStyle(fontWeight = FontWeight.Bold),
                    start, end,
                )
            }
        }
    }

/**
 * 数学块轻着色（纯函数，JVM 可单测）——#488③ 方案 c。
 *
 * 三角色（与 [toCodeSyntaxTheme] 令牌映射同哲学）：
 *  - `\命令`（反斜杠 + 连续字母，整段含反斜杠）→ tertiary：LaTeX 控制
 *    序列是公式的「关键字」；
 *  - 花括号 `{`/`}` 单字符 → onSurfaceVariant：分组结构弱化呈现；
 *  - `^`/`_` 单字符 → secondary：上下标钩子。
 * `\` 后非字母（`\,` `\%` `\\`）为 LaTeX 转义字面量，保持原色
 * （highlights 引擎无 tex 语言，此处手写——区间由本扫描器顺序产出，
 * 天然非重叠、界内，无引擎区间守卫需求）。
 */
internal fun buildMathAnnotatedString(
    code: String,
    commandColor: Color,
    braceColor: Color,
    scriptColor: Color,
): AnnotatedString = buildAnnotatedString {
    append(code)
    var i = 0
    val n = code.length
    while (i < n) {
        val c = code[i]
        when {
            c == '\\' && i + 1 < n && code[i + 1].isLetter() -> {
                var j = i + 1
                while (j < n && code[j].isLetter()) j++
                addStyle(SpanStyle(color = commandColor), i, j)
                i = j
            }
            c == '{' || c == '}' -> {
                addStyle(SpanStyle(color = braceColor), i, i + 1)
                i++
            }
            c == '^' || c == '_' -> {
                addStyle(SpanStyle(color = scriptColor), i, i + 1)
                i++
            }
            else -> i++
        }
    }
}
