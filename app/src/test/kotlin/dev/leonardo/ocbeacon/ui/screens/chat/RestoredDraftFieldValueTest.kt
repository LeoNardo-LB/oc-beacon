package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 拼接可见性 UX（浸泡 2026-10-28 裁决项 ②）：草稿/revert 回填映射的选择域钉死。
 *
 * 全选 TextRange(0, length) 是修复本体——光标置尾（TextRange(length)）会让
 * 新输入静默拼接旧草稿（A-16 真机实证）；全选让旧文本高亮可见+输入覆盖。
 */
class RestoredDraftFieldValueTest {

    @Test
    fun `restore backfill selects full range for overwrite semantics`() {
        val value = restoredDraftFieldValue("OLD DRAFT")
        assertEquals("OLD DRAFT", value.text)
        assertEquals(TextRange(0, 9), value.selection)
    }

    @Test
    fun `empty text maps to empty collapsed selection`() {
        val value = restoredDraftFieldValue("")
        assertEquals("", value.text)
        assertEquals(TextRange(0, 0), value.selection)
    }

    @Test
    fun `multibyte text selection covers full char length`() {
        val text = "草稿✓旧文本"
        val value = restoredDraftFieldValue(text)
        assertEquals(text, value.text)
        assertEquals(TextRange(0, text.length), value.selection)
    }

    @Test
    fun `mapping is a pure function of text`() {
        assertEquals(
            restoredDraftFieldValue("abc"),
            TextFieldValue("abc", TextRange(0, 3)),
        )
    }
}
