package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * splitOversizedParagraphs（超长段落空行化）单测——2026-08-20 第二轮滚动
 * 卡顿修复 C-F1：巨型单段 PARAGRAPH（LLM 清单 "1 - one\n2 - two…"）让块级
 * 分片失效，空行化后每行独立成块 → 分片链路生效。
 */
class SplitOversizedParagraphsTest {

    @Test
    fun shortTextUntouched() {
        val text = "1 - one\n2 - two\n3 - three"
        assertEquals(text, splitOversizedParagraphs(text))
    }

    @Test
    fun oversizedParagraphGetsBlankLines() {
        // 300 行 × 11 字符 ≈ 3600 字符 > 3000 阈值 → 空行化
        val lines = (1..300).joinToString("\n") { "$it - item" }
        val result = splitOversizedParagraphs(lines)
        assertTrue(result.contains("1 - item\n\n2 - item"))
        // 内容行守恒（无丢失）
        val originalLines = lines.split("\n")
        val resultLines = result.split("\n").filter { it.isNotEmpty() }
        assertEquals(originalLines, resultLines)
    }

    @Test
    fun belowThresholdParagraphKeepsSingleNewlines() {
        // 100 行 × 11 字符 ≈ 1100 字符 < 3000 → 原样
        val lines = (1..100).joinToString("\n") { "$it - item" }
        assertEquals(lines, splitOversizedParagraphs(lines))
    }

    @Test
    fun fencedCodeBlockLinesNotSplit() {
        val code = (1..700).joinToString("\n") { "code line $it" }
        val text = "```kotlin\n$code\n```"
        val result = splitOversizedParagraphs(text)
        assertTrue("围栏内容原样", result.contains("\n$code\n"))
        assertFalse(result.contains("code line 1\n\ncode line 2"))
    }

    @Test
    fun tableLinesNotSplit() {
        val rows = (1..400).joinToString("\n") { "| $it | value |" }
        val text = "| h | v |\n$rows"
        val result = splitOversizedParagraphs(text)
        assertFalse(result.contains("| 1 | value |\n\n| 2 |"))
    }

    @Test
    fun listLinesNotSplit() {
        val items = (1..400).joinToString("\n") { "- item $it" }
        assertEquals(items, splitOversizedParagraphs(items))
    }

    @Test
    fun orderedListLinesNotSplit() {
        val items = (1..400).joinToString("\n") { "1. item $it" }
        assertEquals(items, splitOversizedParagraphs(items))
    }

    @Test
    fun mixedContentOnlyPlainRunSplit() {
        val plainBig = (1..200).joinToString("\n") { "plain line number $it with padding text" }
        val text = "```\ncode\n```\n\n$plainBig\n\n- a\n- b"
        val result = splitOversizedParagraphs(text)
        assertTrue("普通长段应被空行化", result.contains("plain line number 1 with padding text\n\nplain line number 2"))
        assertTrue("代码块原样", result.contains("```\ncode\n```"))
        assertTrue("短列表原样", result.endsWith("- a\n- b"))
    }

    @Test
    fun pureTextWholeRunSplit() {
        // 行需足够长使总字符 ≥3000（短输入会被提前原样返回）
        val big = (1..300).joinToString("\n") { "x line number $it" }
        val result = splitOversizedParagraphs(big)
        assertTrue(result.contains("x line number 1\n\nx line number 2"))
    }
}