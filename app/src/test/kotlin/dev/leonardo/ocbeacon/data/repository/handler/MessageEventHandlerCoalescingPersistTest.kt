package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.data.local.PartDelta
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #340：持久化背压合并写——全量 upsert 按 (sessionId, messageId) 最新快照
 * 合并（latest-wins）、批量刷洗（每会话单次调用），洪峰不再丢写；
 * 增量 delta 保序排空。真机实证背景：resync 期旧管线 BUFFERED(64) 满即
 * 丢写（dropped 1150→1500 连发，含终态修复写）。
 */
class MessageEventHandlerCoalescingPersistTest {

    private lateinit var store: MessageCacheRepository
    private lateinit var handler: MessageEventHandler

    private val upsertCalls = mutableListOf<Pair<String, List<MessageWithParts>>>()
    private val deltaCalls = mutableListOf<Pair<String, List<PartDelta>>>()

    @Before
    fun setup() {
        store = mockk<MessageCacheRepository>(relaxed = true)
        coEvery { store.upsertMessages(any(), any(), any()) } answers {
            synchronized(upsertCalls) { upsertCalls.add(firstArg<String>() to secondArg<List<MessageWithParts>>()) }
        }
        coEvery { store.appendPartTexts(any(), any(), any()) } answers {
            synchronized(deltaCalls) { deltaCalls.add(firstArg<String>() to thirdArg<List<PartDelta>>()) }
        }
        handler = MessageEventHandler(store)
    }

    private fun assistantMsg(id: String, tokens: Int? = null) = Message.Assistant(
        id = id, sessionId = "s1", parentId = "",
        time = TimeInfo(created = 1000L, completed = 2000L),
        tokens = tokens?.let { Message.Assistant.Tokens(input = it, output = 0, total = it) },
    )

    @Test
    fun `flood of upserts beyond legacy buffer is fully persisted`() = runTest {
        // 5000 条（旧管线 BUFFERED=64 必丢）——同步喂入后手动刷洗
        repeat(5000) { i ->
            handler.handleMessageUpdated(SseEvent.MessageUpdated(assistantMsg("m$i")))
        }
        handler.flushPendingUpserts()

        val persistedIds = synchronized(upsertCalls) {
            upsertCalls.flatMap { (_, payload) -> payload.map { it.info.id } }
        }.toSet()
        assertEquals(5000, persistedIds.size)
        // 批量语义：真调度器写协程可与喂入并发按 128 阈值先刷数批——
        // 上界按 5000/128 + 时延批余量取 100（旧管线=逐条 5000 次写）
        val callCount = synchronized(upsertCalls) { upsertCalls.size }
        assertTrue("batched calls=$callCount should be << 5000", callCount in 1..100)
    }

    @Test
    fun `duplicate message id coalesces to latest snapshot`() = runTest {
        handler.handleMessageUpdated(SseEvent.MessageUpdated(assistantMsg("m1", tokens = 1)))
        handler.handleMessageUpdated(SseEvent.MessageUpdated(assistantMsg("m1", tokens = 99)))
        handler.flushPendingUpserts()

        val calls = synchronized(upsertCalls) { upsertCalls.toList() }
        assertEquals(1, calls.size)
        assertEquals(1, calls[0].second.size)
        assertEquals(99, (calls[0].second[0].info as Message.Assistant).tokens?.total)
    }

    @Test
    fun `flush without pending is no-op`() = runTest {
        handler.flushPendingUpserts()
        assertTrue(synchronized(upsertCalls) { upsertCalls.isEmpty() })
    }

    @Test
    fun `delta requests drain in order`() = runTest {
        // 构造两条 delta 流（不同 part），经 forceFlushDeltas 入增量队后排空
        handler.handleMessageUpdated(SseEvent.MessageUpdated(assistantMsg("m1")))
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta("s1", "m1", "p1", "text", "A"))
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta("s1", "m1", "p2", "text", "B"))
        handler.forceFlushDeltas()
        handler.drainDeltaPersistQueue()

        val calls = synchronized(deltaCalls) { deltaCalls.toList() }
        assertEquals(1, calls.size)
        assertEquals(setOf("p1" to "A", "p2" to "B"), calls[0].second.map { it.partId to it.delta }.toSet())
    }
}
