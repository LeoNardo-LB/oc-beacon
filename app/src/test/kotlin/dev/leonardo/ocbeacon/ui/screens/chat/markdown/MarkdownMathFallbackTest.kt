package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #312② 方案 C：transformMathFallback（数学定界符降级）单测。
 *
 * multiplatform-markdown-renderer 0.45.0 无 math 扩展（已取证）——裁决为预变换：
 * 检测 $$...$$（块级，含多行）与 \(...\)（行内）/ \[...\]（块级）成对定界符，
 * 替换为 tex 围栏代码块（块级）或行内代码（行内）——渲染器既有等宽代码渲染，
 * 公式原样可读且有视觉区分。不碰渲染器、不增依赖。
 *
 * 边界钉子：块级/行内/不成对保留原文/多段/$$ 嵌入既有围栏代码内不误伤/
 * 单 $ 货币不误伤/幂等（已变换不重复）。
 *
 * 源码占位：Kotlin 字符串中 $ 后跟字母触发插值——数学定界符统一以 ¤ 写出、
 * [dollar] 运行时还原，保持用例可读。
 */
class MarkdownMathFallbackTest {

    private fun dollar(s: String): String = s.replace('\u00A4', '$')

    // ============ 块级 $$...$$ ============

    @Test
    fun blockDollarSingleLineStandalone() {
        assertEquals(
            "```tex\nE=mc^2\n```",
            transformMathFallback(dollar("\u00A4\u00A4E=mc^2\u00A4\u00A4")),
        )
    }

    @Test
    fun blockDollarMultiline() {
        assertEquals(
            "```tex\nE=mc^2\n\\frac{a}{b}\n```",
            transformMathFallback(dollar("\u00A4\u00A4\nE=mc^2\n\\frac{a}{b}\n\u00A4\u00A4")),
        )
    }

    @Test
    fun blockDollarSurroundedByProse() {
        assertEquals(
            "前文\n```tex\nx^2\n```\n后文",
            transformMathFallback(dollar("前文\n\u00A4\u00A4x^2\u00A4\u00A4\n后文")),
        )
    }

    @Test
    fun blockDollarMidLineSplitsToFence() {
        // CommonMark 围栏须在行首：行中 $$ 前后补换行隔断（fence 可打断段落）
        assertEquals(
            "值 \n```tex\nx\n```\n 为",
            transformMathFallback(dollar("值 \u00A4\u00A4x\u00A4\u00A4 为")),
        )
    }

    @Test
    fun consecutiveBlockPairsSameLine() {
        assertEquals(
            "```tex\na\n```\n then \n```tex\nb\n```",
            transformMathFallback(dollar("\u00A4\u00A4a\u00A4\u00A4 then \u00A4\u00A4b\u00A4\u00A4")),
        )
    }

    // ============ 行内 \(...\) 与 块级 \[...\] ============

    @Test
    fun inlineParenToInlineCode() {
        assertEquals(
            "设 `x^2+y^2` 为变量",
            transformMathFallback("设 \\(x^2+y^2\\) 为变量"),
        )
    }

    @Test
    fun inlineParenDoesNotSpanLines() {
        // 行内数学不跨行：闭合定界符必须与开启定界符同行
        val input = "a \\(x\ny\\) b"
        assertEquals(input, transformMathFallback(input))
    }

    @Test
    fun blockBracketMultiline() {
        assertEquals(
            "推导：\n```tex\n\\int_0^1 x\\,dx\n```\n完毕",
            transformMathFallback("推导：\n\\[\n\\int_0^1 x\\,dx\n\\]\n完毕"),
        )
    }

    // ============ 多段 ============

    @Test
    fun multipleSegmentsMixed() {
        assertEquals(
            "```tex\na\n```\n\nmiddle\n\n`b` tail",
            transformMathFallback(dollar("\u00A4\u00A4a\u00A4\u00A4\n\nmiddle\n\n") + "\\(b\\) tail"),
        )
    }

    // ============ 不成对 / 货币 / 空对 ============

    @Test
    fun unpairedDelimitersKeptVerbatim() {
        listOf(dollar("孤 \u00A4\u00A4x 无闭合"), "半 \\(个", "半 \\[个").forEach { input ->
            assertEquals(input, transformMathFallback(input))
        }
    }

    @Test
    fun singleDollarCurrencyUntouched() {
        listOf(
            "价格 $5 与 $10 共 $15",
            "a $ b $ c",
            dollar("\u00A4math\u00A4 单美元数学不支持（货币误伤防护）"),
        ).forEach { input ->
            assertEquals(input, transformMathFallback(input))
        }
    }

    @Test
    fun emptyPairsUntouched() {
        listOf(dollar("\u00A4\u00A4\u00A4\u00A4"), "\\(\\)").forEach { input ->
            assertEquals(input, transformMathFallback(input))
        }
    }

    @Test
    fun strayDollarsAcrossParagraphsNotPaired() {
        // 两个孤 $$ 分处不同段落：不得跨空行配成一个巨型公式块
        val input = dollar("\u00A4\u00A4x\n\nparagraph\n\n\u00A4\u00A4y")
        assertEquals(input, transformMathFallback(input))
    }

    // ============ 既有围栏代码块内不误伤 ============

    @Test
    fun dollarInsideFencedCodeUntouched() {
        listOf(
            "```kotlin\nval s = \"" + dollar("\u00A4\u00A4x\u00A4\u00A4") + "\"\n```",
            "~~~\n" + dollar("\u00A4\u00A4x\u00A4\u00A4") + "\n~~~",
            "```\ncode " + dollar("\u00A4\u00A4x\u00A4\u00A4") + " 未闭合围栏后续全跳过",
            "```\n\\(x\\) \\[y\\]\n```",
        ).forEach { input ->
            assertEquals(input, transformMathFallback(input))
        }
    }

    @Test
    fun mathAfterFenceStillTransformed() {
        assertEquals(
            "```\ncode\n```\n```tex\nx\n```",
            transformMathFallback("```\ncode\n```\n" + dollar("\u00A4\u00A4x\u00A4\u00A4")),
        )
    }

    @Test
    fun inlineCodeSpanUntouched() {
        // 行内代码 span 内的 $$ 字面量不误伤（兼作幂等保护）
        val input = "`" + dollar("\u00A4\u00A4x\u00A4\u00A4") + "` 字面量"
        assertEquals(input, transformMathFallback(input))
    }

    // ============ 幂等 ============

    @Test
    fun idempotentOnTransformedOutput() {
        val sample = dollar("\u00A4\u00A4a\u00A4\u00A4\nprose ") + "\\(b\\)\n\\[c\\]\n```kotlin\nval " +
            dollar("\u00A4\u00A4z\u00A4\u00A4") + " = 1\n```"
        val once = transformMathFallback(sample)
        assertEquals(once, transformMathFallback(once))
    }

    // ============ 快路径 ============

    @Test
    fun plainTextAndEmptyUntouched() {
        listOf("", "普通文本，无数学。", "# 标题\n\n- 列表项").forEach { input ->
            assertEquals(input, transformMathFallback(input))
        }
    }

    // ============ 接线（normalizeForRender 管点） ============

    @Test
    fun normalizeForRenderAppliesMathFallback() {
        val out = normalizeForRender(
            "结论：\n" + dollar("\u00A4\u00A4") + "\nE=mc^2\n" + dollar("\u00A4\u00A4") + "\n完毕",
            isUser = false,
        )
        assertEquals("结论：\n```tex\nE=mc^2\n```\n完毕", out)
    }

    @Test
    fun normalizeForRenderUserMessageAlsoTransformed() {
        val out = normalizeForRender(dollar("\u00A4\u00A4") + "\nx\n" + dollar("\u00A4\u00A4"), isUser = true)
        // 用户路径单换行空行化发生在数学降级之后：围栏内容行距放宽，但仍是合法 tex 围栏
        assertEquals("```tex\n\nx\n\n```", out)
    }
}
