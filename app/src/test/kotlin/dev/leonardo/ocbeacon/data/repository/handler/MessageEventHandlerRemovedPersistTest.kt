package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四层根修（2026-09-09）：echo 拆除的 Room 行删除。
 *
 * 实测链路（session-a5b0 活体）：pending-* 回显行拆除只清内存——热表幽灵行
 * 留存，任何 Room 回灌（backfill/窗口重载）把幽灵重新 materialize：压缩后
 * 幽灵气泡重回 UI、快速定位列出不可跳转条目（seq 键控机制对非 seq id 恒失配）。
 * MessageRemoved 现在同时删 Room 行（事务：行+parts+FTS）。
 */
class MessageEventHandlerRemovedPersistTest {

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test
    fun `message removed deletes room row asynchronously`() {
        val store = mockk<MessageCacheRepository>(relaxed = true)
        val handler = MessageEventHandler(store)
        var deleted = false
        handler.handleMessageRemoved(SseEvent.MessageRemoved(sessionId = "s1", messageId = "pending-abc"))
        assertTrue("deleteMessage 应在宽限期内到达（batchScope 异步）", awaitUntil {
            runCatching {
                coVerify(exactly = 1) { store.deleteMessage("s1", "pending-abc") }
                deleted = true
            }.isSuccess && deleted
        })
    }

    @Test
    fun `null store skips room delete without crash`() {
        val handler = MessageEventHandler(null)
        handler.handleMessageRemoved(SseEvent.MessageRemoved(sessionId = "s1", messageId = "x"))
        // 测试环境无 store：仅内存拆除，不抛异常即通过
        assertTrue(true)
    }
}
