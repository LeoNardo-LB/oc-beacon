package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-08-26 流式卡顿根因修复的哨兵等价性测试：
 * 三个前置哨兵（无 | 跳表格正则 / 无任务字符跳标记阶段 / 手写有序列表判定）
 * 必须与原全量路径逐字节等价。
 */
class NormalizeSentinelEquivalenceTest {

    // ============ 表格哨兵：无 '|' → 原样返回；有 '|' → 原语义 ============

    @Test
    fun `table sentinel passthrough when no pipe char`() {
        val text = "纯文本段落。\n第二行继续 essay 内容。\n\n# 标题\n- 列表项"
        assertEquals(text, ensureBlankLineBeforeGfmTables(text))
    }

    @Test
    fun `table sentinel still inserts blank line for real table`() {
        val text = "前言段落\n|A|B|\n|--|--|"
        val out = ensureBlankLineBeforeGfmTables(text)
        assertEquals("前言段落\n\n|A|B|\n|--|--|", out)
    }

    @Test
    fun `table sentinel untouched for table already after blank line`() {
        val text = "前言\n\n|A|B|\n|--|--|"
        assertEquals(text, ensureBlankLineBeforeGfmTables(text))
    }

    @Test
    fun `table sentinel pipe in plain text without table does not corrupt`() {
        // 有 | 但不构成表格（无分隔行）——正则仍跑但不命中
        val text = "a|b\nc|d"
        assertEquals(text, ensureBlankLineBeforeGfmTables(text))
    }

    // ============ 任务标记哨兵 ============

    @Test
    fun `task sentinel passthrough when no marker chars`() {
        val md = "# 标题\n\n```kotlin\nval x = 1\n```\n\n- 普通列表\n正文"
        assertEquals(md, normalizeTaskListMarkers(md))
    }

    @Test
    fun `task sentinel still normalizes markers outside fences`() {
        val md = "- [ ] 空任务\n- ☐ 未选\n- ☑ 已选\n- ✅ 完成"
        val out = normalizeTaskListMarkers(md)
        assertFalse(out.contains("☐"))
        assertFalse(out.contains("☑"))
        assertFalse(out.contains("✅"))
        assertTrue(out.contains("- [ ] 未选"))
        assertTrue(out.contains("- [x] 已选"))
    }

    @Test
    fun `task sentinel preserves markers inside fences`() {
        val md = "```\n- ☐ 围栏内不改\n```\n- ☐ 围栏外改"
        val out = normalizeTaskListMarkers(md)
        assertTrue(out.contains("围栏内不改"))   // ☐ 保留在围栏内
        assertTrue(out.contains("- [ ] 围栏外改"))
    }

    // ============ 有序列表手写判定 vs 原正则 ============

    @Test
    fun `ordered list hand check matches regex semantics`() {
        // 正例（原正则 ^\d{1,9}[.)]\s 命中）
        for (s in listOf("1. a", "1) a", "123. x", "123456789. y", "42. tab-after-dot")) {
            assertTrue("should match: $s", OrderedListItemRegex.containsMatchIn(s))
        }
        // 反例
        for (s in listOf("a. x", "12345678901. too-many-digits", "1.x", "1.", " 1. indented-regex-anchored",
                     "1234567890. ten-digits", "1.-dash", "42\ttab-no-delimiter", "")) {
            assertFalse("should not match: $s", OrderedListItemRegex.containsMatchIn(s))
        }
    }

    @Test
    fun `split oversized paragraphs end-to-end unchanged`() {
        // 端到端：长 plain 段落（>3000 字符）空行化行为不变
        val longText = (1..80).joinToString("\n") { "line $it with some content text here padding" }
        assertTrue(longText.length > 3000)
        val out = splitOversizedParagraphs(longText)
        // 行间补空行 → 行数接近翻倍
        assertTrue(out.contains("\n\n"))
        assertEquals(longText.lines().size, out.lines().filter { it.isNotBlank() }.size)
    }
}
