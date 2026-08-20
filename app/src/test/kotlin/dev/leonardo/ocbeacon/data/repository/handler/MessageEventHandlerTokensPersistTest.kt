package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1657（P3）：SSE_PRIORITY 合并的 tokens/cost 增量落库。
 *
 * V2 SSE 整 turn 不发 message.updated，REST 刷新是 tokens 唯一可靠来源；
 * upsertSsePriority 原本只更新内存热视图 → cached_messages.payload 停留在
 * 流式期骨架快照（tokens=null）→ 冷启动/离线 seed 后统计图标短暂缺失。
 * 修复：合并前后 tokens/cost 对比（不在 existing = null→值 视为变更），
 * 变更行经 persistSseUpdate 增量落盘；值未变 0 写库（检测即节流）。
 *
 * 持久化经 persistQueue 单写协程异步消费：正断言轮询等待（5s 上限）；
 * 负断言在同测试内先以正路径证明管道通畅，再给宽限期验证 0 追加写。
 */
class MessageEventHandlerTokensPersistTest {

    private lateinit var store: MessageCacheRepository
    private lateinit var handler: MessageEventHandler

    /** persist actor 消费到的全量 upsert payload（跨线程记录）。 */
    private val persistedPayloads = mutableListOf<List<MessageWithParts>>()

    @Before
    fun setup() {
        store = mockk<MessageCacheRepository>(relaxed = true)
        coEvery { store.upsertMessages(any(), any(), any()) } answers {
            val payload: List<MessageWithParts> = secondArg()
            synchronized(persistedPayloads) { persistedPayloads.add(payload) }
        }
        handler = MessageEventHandler(store)
    }

    private val tokens = Message.Assistant.Tokens(input = 120, output = 80, reasoning = 10)

    private fun assistantMsg(
        id: String,
        tokens: Message.Assistant.Tokens? = null,
        cost: Double? = null,
    ) = Message.Assistant(
        id = id,
        sessionId = "s1",
        parentId = "",
        time = TimeInfo(created = 1000L, completed = 2000L),
        modelId = "test-model",
        tokens = tokens,
        cost = cost,
    )

    private fun persistedCount(): Int = synchronized(persistedPayloads) { persistedPayloads.size }

    /** 轮询等待 persist actor 消费 [expected] 批全量写（超时 false）。 */
    private fun awaitPersisted(expected: Int, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (persistedCount() >= expected) return true
            Thread.sleep(20)
        }
        return persistedCount() >= expected
    }

    @Test
    fun `tokens null to value triggers persist with final payload`() {
        // 重进会话场景：内存热视图为空，REST 刷新（SSE_PRIORITY）带回带 tokens 的行
        handler.upsertMessages(
            "s1",
            listOf(MessageWithParts(assistantMsg("m1", tokens, cost = 0.01), emptyList())),
            MergeStrategy.SSE_PRIORITY,
        )
        assertTrue("tokens 变更行应在超时前经 persistQueue 落库", awaitPersisted(1))

        val payload = synchronized(persistedPayloads) { persistedPayloads.first() }
        assertEquals(listOf("m1"), payload.map { it.info.id })
        val persisted = payload.single().info as Message.Assistant
        // 落库 payload 必须携带 tokens/cost（cached_messages.payload = 完整 Message JSON）
        assertEquals(tokens, persisted.tokens)
        assertEquals(0.01, persisted.cost!!, 0.0)
    }

    @Test
    fun `unchanged tokens on repeated refresh does not persist again`() {
        val incoming = listOf(MessageWithParts(assistantMsg("m1", tokens), emptyList()))
        handler.upsertMessages("s1", incoming, MergeStrategy.SSE_PRIORITY)
        assertTrue("首次刷新（null→值）应落库一次", awaitPersisted(1))

        // 第二次相同 REST 刷新：内存已带同值 tokens → 合并前后无变化 → 0 追加写
        handler.upsertMessages("s1", incoming, MergeStrategy.SSE_PRIORITY)
        Thread.sleep(500) // 负断言宽限期（上方已证明管道通畅）
        assertEquals("值未变不应重复写库", 1, persistedCount())
        coVerify(exactly = 1) { store.upsertMessages("s1", any(), any()) }
    }

    @Test
    fun `rows without tokens change are not persisted`() {
        // user 行与 tokens=null 的 assistant 行：无 tokens/cost 变化 → 不触发落库
        val userMsg = Message.User(
            id = "u1",
            sessionId = "s1",
            role = "user",
            time = TimeInfo(created = 500L),
        )
        handler.upsertMessages(
            "s1",
            listOf(
                MessageWithParts(
                    userMsg,
                    listOf(Part.Text(id = "u1_text", sessionId = "s1", messageId = "u1", text = "hi")),
                ),
                MessageWithParts(assistantMsg("m1"), emptyList()), // tokens=null → 无变化
            ),
            MergeStrategy.SSE_PRIORITY,
        )
        assertEquals(0, persistedCount())
        coVerify(exactly = 0) { store.upsertMessages(any(), any(), any()) }
    }

    @Test
    fun `streaming deltas with unchanged tokens keep full-row writes throttled`() {
        // 会话内流式：消息已带 tokens；delta 批处理期间的 REST 刷新不重复整行写库
        val part = Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel")
        handler.upsertMessages(
            "s1",
            listOf(MessageWithParts(assistantMsg("m1", tokens), listOf(part))),
            MergeStrategy.SSE_PRIORITY,
        )
        assertTrue(awaitPersisted(1))

        // 流式 delta：48ms 批处理 → 增量 append（appendPartTexts 路径，非整行重写）
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = "m1_text_ord_0", field = "text", delta = "l",
        ))
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = "m1_text_ord_0", field = "text", delta = "o",
        ))
        handler.forceFlushDeltas()

        // tokens 未变的流式中间态刷新：不触发整行 upsert（节流 = 变更检测）
        handler.upsertMessages(
            "s1",
            listOf(MessageWithParts(
                assistantMsg("m1", tokens),
                listOf(Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hello")),
            )),
            MergeStrategy.SSE_PRIORITY,
        )
        Thread.sleep(500)
        assertEquals("流式中间 tokens 未变：整行写库不增加", 1, persistedCount())
        coVerify(exactly = 1) { store.upsertMessages(any(), any(), any()) }
        // delta 仍走增量路径（48ms 批处理管线的增量落盘不受影响）
        coVerify(atLeast = 1) { store.appendPartTexts("s1", any(), any()) }
    }
}
