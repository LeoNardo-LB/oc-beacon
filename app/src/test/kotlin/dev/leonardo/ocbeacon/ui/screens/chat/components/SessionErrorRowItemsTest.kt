package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转录内错误行映射（D1③ 对齐 DSH turn-error 语义）：会话运行错误渲染为
 * 消息流（ChatMessageList LazyColumn）里的错误行 item —— 随历史滚动，
 * 非悬浮/常驻浮层（DSH TurnErrorItem 即转录内 status 行，无 dismiss）。
 * 本契约锁定：每条错误 → 一个稳定 key 的列表项（LazyColumn 按 key 差分，
 * 稳定 key 保证行增删时无漂移）。
 */
class SessionErrorRowItemsTest {

    @Test
    fun `each error maps to one in-transcript row item with stable key`() {
        val items = sessionErrorRowItems(listOf("provider rejected: balance", "agent crash"))
        assertEquals(
            listOf(
                "session_error_0" to "provider rejected: balance",
                "session_error_1" to "agent crash",
            ),
            items,
        )
    }

    @Test
    fun `no errors yield no rows`() {
        assertTrue(sessionErrorRowItems(emptyList()).isEmpty())
    }
}
