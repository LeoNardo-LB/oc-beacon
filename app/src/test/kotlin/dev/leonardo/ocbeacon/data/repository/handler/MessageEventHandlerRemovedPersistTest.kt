package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
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

    // ===== #437 验收十五轮：写序竞态根修（播种 upsert × 拆除 delete）=====

    private fun userMsg(id: String) = Message.User(
        id = id, sessionId = "s1", time = TimeInfo(created = 1000L),
    )

    @Test
    fun `removal withdraws queued upsert before flush - ghost row never written`() = runTest {
        // 回归（真机 flicker3）：pending-* 播种 upsert 还在合并缓冲（250ms 时延批）
        // 内时拆除——原并行 delete 与后到 upsert 事务无写序保证（upsert 晚 167ms
        // 提交重插已删行 → 幽灵气泡复活挂屏 6 分钟）。现在：拆除先从缓冲撤下未写
        // 行，删除由单写协程串行执行，upsert 批永不包含已拆 id。
        val store = mockk<MessageCacheRepository>(relaxed = true)
        val upserts = mutableListOf<List<MessageWithParts>>()
        coEvery { store.upsertMessages(any(), any(), any()) } answers {
            synchronized(upserts) { upserts.add(secondArg()) }
        }
        val handler = MessageEventHandler(store)
        handler.handleMessageUpdated(SseEvent.MessageUpdated(userMsg("pending-x")))
        handler.handleMessageRemoved(SseEvent.MessageRemoved(sessionId = "s1", messageId = "pending-x"))
        handler.flushPendingUpserts()
        handler.flushPendingDeletes()
        assertTrue(
            "upsert 批不得包含已拆 id",
            synchronized(upserts) { upserts.flatten() }.none { it.info.id == "pending-x" },
        )
        coVerify(exactly = 1) { store.deleteMessage("s1", "pending-x") }
    }

    @Test
    fun `re-arrival after completed delete revives row`() = runTest {
        // 事件时间最后操作=upsert：删除完成（模拟写协程先行）后再到达
        //（合法重放/换装后回填）必须重新落库——陈旧删除不得吞掉后续重放。
        val store = mockk<MessageCacheRepository>(relaxed = true)
        val handler = MessageEventHandler(store)
        handler.handleMessageRemoved(SseEvent.MessageRemoved(sessionId = "s1", messageId = "m9"))
        handler.flushPendingDeletes() // 删除已完成
        handler.handleMessageUpdated(SseEvent.MessageUpdated(userMsg("m9")))
        handler.flushPendingUpserts()
        coVerify(exactly = 1) { store.deleteMessage("s1", "m9") }
        coVerify(atLeast = 1) { store.upsertMessages("s1", any(), any()) }
    }

    @Test
    fun `delete serializes after in-flight upsert batch`() = runTest {
        // upsert 批已提取（模拟写协程事务进行中）后拆除到达：删除入队，下一轮
        // 由同一写协程执行——顺序恒为 upsert→delete（旧实现并行 delete 可先跳）。
        val store = mockk<MessageCacheRepository>(relaxed = true)
        val handler = MessageEventHandler(store)
        handler.handleMessageUpdated(SseEvent.MessageUpdated(userMsg("m1")))
        handler.flushPendingUpserts() // 批已落库（等价 in-flight 已提交）
        handler.handleMessageRemoved(SseEvent.MessageRemoved(sessionId = "s1", messageId = "m1"))
        handler.flushPendingDeletes()
        coVerify(exactly = 1) { store.upsertMessages(any(), any(), any()) }
        coVerify(exactly = 1) { store.deleteMessage("s1", "m1") }
    }
}
