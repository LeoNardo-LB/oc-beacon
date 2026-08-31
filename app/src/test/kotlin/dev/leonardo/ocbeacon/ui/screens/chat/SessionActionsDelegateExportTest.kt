package dev.leonardo.ocbeacon.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #276 终验 V6：ZIP 导出（DSH session.export）落盘显示名规范——
 * SAF 建议名由 ChatScreen 固定 $slug.json（本轮冻结），
 * 导出内容却是 ZIP 流：写盘前把显示名转成 .zip。
 */
class SessionActionsDelegateExportTest {

    @Test
    fun `zipExportDisplayName replaces json suffix`() {
        assertEquals("session.zip", zipExportDisplayName("session.json"))
        assertEquals("Report v1.2.zip", zipExportDisplayName("Report v1.2.json"))
        assertEquals("a.ZIP", zipExportDisplayName("a.ZIP")) // 已 .zip 不动（保留原大小写）
    }

    @Test
    fun `zipExportDisplayName appends when no json suffix`() {
        assertEquals("session.zip", zipExportDisplayName("session"))
        assertEquals("notes.txt.zip", zipExportDisplayName("notes.txt"))
        assertEquals("session.zip", zipExportDisplayName(""))
    }
}
