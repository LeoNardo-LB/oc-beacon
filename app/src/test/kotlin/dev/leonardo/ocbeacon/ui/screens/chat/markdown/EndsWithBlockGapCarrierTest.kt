package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #517 终末块距载体判定——内容末块恰为代码/公式块（闭合围栏收尾）时，
 * turn 终态下其 bottom 外距修剪（内容→统计栏间隙与 user 侧对齐）。
 */
class EndsWithBlockGapCarrierTest {

    @Test
    fun `闭合围栏收尾判定为载体`() {
        assertTrue(endsWithBlockGapCarrier("前言\n\n```kotlin\nfun a() {}\n```\n"))
        assertTrue(endsWithBlockGapCarrier("```bash\nls\n```"))          // 无尾换行
        assertTrue(endsWithBlockGapCarrier("~~~\ncode\n~~~\n\n"))       // 波浪线围栏 + 尾空行
    }

    @Test
    fun `围栏后还有内容不是载体`() {
        assertFalse(endsWithBlockGapCarrier("```\ncode\n```\n后文段落"))
        assertFalse(endsWithBlockGapCarrier("```\ncode\n```\n\n结语。"))
    }

    @Test
    fun `纯文本与开栏态不是载体`() {
        assertFalse(endsWithBlockGapCarrier("普通散文段落。"))
        assertFalse(endsWithBlockGapCarrier("```kotlin\nfun a() {}"))    // 未闭栏（流式中）
        assertFalse(endsWithBlockGapCarrier(""))
    }

    @Test
    fun `围栏行带尾随内容不是载体（围栏内代码行误判防御）`() {
        // ````x` 形态（info 串）是开栏行非闭栏行；闭栏行标记后必须全空白
        assertFalse(endsWithBlockGapCarrier("```\ncode\n```\nx"))
        // 缩进闭栏（≤3 空格）合法
        assertTrue(endsWithBlockGapCarrier("```\ncode\n   ```\n"))
    }
}
