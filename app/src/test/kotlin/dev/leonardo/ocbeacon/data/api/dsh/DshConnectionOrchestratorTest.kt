package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DshConnectionOrchestrator 注入缝编排测试（backlog #276 步骤⑤；设计 §2.3/§1.6-5）。
 *
 * 假帧源 + 假历史源（虚拟时钟驱动基线静默窗）——断言 processEvent 调用序、
 * tracker 水位、对账回填调用形态、Vanished→SessionDeleted、SessionUpdated 防御。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DshConnectionOrchestratorTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 假帧源：测试手工投帧（模拟 OkHttp 读线程回调）。 */
    private class FakeFrameSource : DshFrameSource {
        private val _state = MutableStateFlow(DshWsConnectionState.Disconnected)
        override val connectionState: StateFlow<DshWsConnectionState> = _state.asStateFlow()
        var started = false; private set
        var stopped = false; private set
        lateinit var onFrame: (String, JsonObject, String) -> Unit

        fun connect() { _state.value = DshWsConnectionState.Connected }
        fun drop() { _state.value = DshWsConnectionState.Disconnected }

        override fun start(baseUrl: String, onFrame: (method: String, payload: JsonObject, rpcId: String) -> Unit) {
            started = true; this.onFrame = onFrame
        }

        override fun stop() { stopped = true }
    }

    /** 假历史源：记录请求 + 脚本化页。 */
    private class FakeHistorySource(val pages: Map<String, List<DshHistoryPage>>) : DshHistorySource {
        val requests = mutableListOf<Triple<String, Long?, Int>>()
        override suspend fun fetchPage(sessionId: String, beforeSeq: Long?, maxMessages: Int): DshHistoryPage {
            requests += Triple(sessionId, beforeSeq, maxMessages)
            return pages[sessionId]?.let { list ->
                // 每会话顺序翻页脚本（超出脚本 → 页尽）
                val index = requests.count { (sid, _, _) -> sid == sessionId } - 1
                list.getOrNull(index) ?: DshHistoryPage(rows = emptyList(), hasMore = false, minSeq = null)
            } ?: DshHistoryPage(rows = emptyList(), hasMore = false, minSeq = null)
        }
    }

    private fun obj(text: String) = json.parseToJsonElement(text).jsonObject

    private fun userMessageRow(seq: Long, text: String): JsonObject = obj(
        """{"event":{"type":"user/message","seq":$seq,"time":${seq + 1000},"data":{"content":[{"type":"text","text":"$text"}],"source":{"kind":"user"}}}}"""
    )

    private class Recording {
        val dispatched = mutableListOf<SseEvent>()
        val notified = mutableListOf<SseEvent>()
        val connected = mutableListOf<Boolean>()
        var cache: MutableMap<String, Session> = mutableMapOf()
    }

    private fun orchestrator() = DshConnectionOrchestrator()

    @Test
    fun `live frames dispatch in order and advance tracker`() = runTest {
        val source = FakeFrameSource()
        val rec = Recording()
        val tracker = DshSessionSeqTracker()
        val job = launch {
            orchestrator().run(
                baseUrl = "http://x",
                frameSource = source,
                historySource = FakeHistorySource(emptyMap()),
                tracker = tracker,
                dispatch = { rec.dispatched += it },
                onEvent = { rec.notified += it },
                sessionLookup = { rec.cache[it] },
                onConnected = { rec.connected += it },
            )
        }
        runCurrent() // 让 launch 先执行到 start()（onFrame 就绪）
        source.connect()
        source.onFrame(
            "session/event",
            obj("""{"type":"session/event","sessionId":"s1","event":{"type":"user/message","seq":16,"time":11,"data":{"content":[{"type":"text","text":"live"}],"source":{"kind":"user"}}}}"""),
            "rpc-live-1",
        )
        runCurrent()
        // user/message 映射 = MessageUpdated + 显式 text part（2 事件，保序）
        assertEquals(2, rec.dispatched.size)
        assertTrue(rec.dispatched[0] is SseEvent.MessageUpdated)
        assertTrue(rec.dispatched[1] is SseEvent.MessagePartUpdated)
        assertEquals(rec.dispatched, rec.notified) // onEvent 与 dispatch 同序同集
        assertEquals(16L, tracker.get("s1"))
        assertTrue(rec.connected.last())
        job.cancel()
        runCurrent()
        assertTrue(source.stopped) // finally 兜底停帧源
    }

    @Test
    fun `subscribed baseline settles then initial fetches tail page and replays`() = runTest {
        val source = FakeFrameSource()
        val rec = Recording()
        val tracker = DshSessionSeqTracker()
        val history = FakeHistorySource(
            mapOf(
                "s1" to listOf(
                    DshHistoryPage(
                        rows = listOf(userMessageRow(15, "old"), userMessageRow(20, "new")),
                        hasMore = true,
                        minSeq = 15L,
                    ),
                    DshHistoryPage(rows = listOf(userMessageRow(3, "ancient")), hasMore = false, minSeq = 3L),
                ),
            ),
        )
        val job = launch {
            orchestrator().run(
                "http://x", source, history, tracker,
                dispatch = { rec.dispatched += it },
                onEvent = { rec.notified += it },
                sessionLookup = { rec.cache[it] },
                onConnected = {},
            )
        }
        runCurrent() // 让 launch 先执行到 start()（onFrame 就绪）
        source.onFrame("session/subscribed", obj("""{"type":"session/subscribed","sessionId":"s1","lastSeq":20}"""), "rpc-sub-1")
        advanceTimeBy(500) // 静默窗落定 → 对账
        runCurrent()
        // InitialFetch：只取尾页（beforeSeq=baseline=20，maxMessages=50）
        assertEquals(listOf(Triple("s1", 20L, 50)), history.requests)
        // 尾页两行重放为 2 条 MessageUpdated（保序）
        val updates = rec.dispatched.filterIsInstance<SseEvent.MessageUpdated>()
        assertEquals(listOf("seq-15", "seq-20"), updates.map { it.info.id })
        assertEquals(20L, tracker.get("s1"))
        job.cancel()
    }

    @Test
    fun `gap backfill pages backward until overlap with local watermark`() = runTest {
        val source = FakeFrameSource()
        val rec = Recording()
        val tracker = DshSessionSeqTracker()
        tracker.applied("s1", 5L) // 本地已有 1..5
        val history = FakeHistorySource(
            mapOf(
                "s1" to listOf(
                    DshHistoryPage(rows = listOf(userMessageRow(6, "a"), userMessageRow(12, "b")), hasMore = true, minSeq = 6L),
                ),
            ),
        )
        val job = launch {
            orchestrator().run(
                "http://x", source, history, tracker,
                dispatch = { rec.dispatched += it },
                onEvent = { rec.notified += it },
                sessionLookup = { rec.cache[it] },
                onConnected = {},
            )
        }
        runCurrent() // 让 launch 先执行到 start()（onFrame 就绪）
        source.onFrame("session/subscribed", obj("""{"type":"session/subscribed","sessionId":"s1","lastSeq":12}"""), "r")
        advanceTimeBy(500)
        runCurrent()
        // Backfill：缺 6..12 → 尾页覆盖即重叠（minSeq 6 <= local 5+1）→ 单页止
        assertEquals(1, history.requests.size)
        assertEquals(12L, tracker.get("s1"))
        job.cancel()
    }

    @Test
    fun `vanished session dispatches SessionDeleted and clears tracker`() = runTest {
        val source = FakeFrameSource()
        val rec = Recording()
        val tracker = DshSessionSeqTracker()
        tracker.applied("gone", 9L)
        val history = FakeHistorySource(emptyMap())
        val job = launch {
            orchestrator().run(
                "http://x", source, history, tracker,
                dispatch = { rec.dispatched += it },
                onEvent = { rec.notified += it },
                sessionLookup = { rec.cache[it] },
                onConnected = {},
            )
        }
        runCurrent() // 让 launch 先执行到 start()（onFrame 就绪）
        // 新基线只剩 s-keep：gone 不在 → Vanished
        source.onFrame("session/subscribed", obj("""{"type":"session/subscribed","sessionId":"s-keep","lastSeq":1}"""), "r")
        advanceTimeBy(500)
        runCurrent()
        val deleted = rec.dispatched.filterIsInstance<SseEvent.SessionDeleted>()
        assertEquals(listOf("gone"), deleted.map { it.info.id })
        assertEquals(null, tracker.get("gone"))
        job.cancel()
    }

    @Test
    fun `minimal SessionUpdated merges directory and created from cache`() = runTest {
        val source = FakeFrameSource()
        val rec = Recording()
        rec.cache["s1"] = Session(
            id = "s1",
            directory = "/home/user/project",
            title = "Old",
            time = Session.Time(created = 111L, updated = 999L),
        )
        val tracker = DshSessionSeqTracker()
        val job = launch {
            orchestrator().run(
                "http://x", source, FakeHistorySource(emptyMap()), tracker,
                dispatch = { rec.dispatched += it },
                onEvent = { rec.notified += it },
                sessionLookup = { rec.cache[it] },
                onConnected = {},
            )
        }
        runCurrent() // 让 launch 先执行到 start()（onFrame 就绪）
        // session/title 产物：title + updated，directory 空、created=0
        source.onFrame(
            "session/event",
            obj("""{"type":"session/event","sessionId":"s1","event":{"type":"session/title","seq":30,"time":1788109001000,"data":{"title":"New Title"}}}"""),
            "r",
        )
        runCurrent()
        val updated = rec.dispatched.filterIsInstance<SseEvent.SessionUpdated>().single()
        assertEquals("New Title", updated.info.title) // 新标题胜出
        assertEquals("/home/user/project", updated.info.directory) // 防御：缓存目录不丢
        assertEquals(111L, updated.info.time.created) // 防御：缓存 created 不丢
        assertEquals(1788109001000L, updated.info.time.updated)
        job.cancel()
    }

    @Test
    fun `host session-added merges created time from cache when present`() = runTest {
        val source = FakeFrameSource()
        val rec = Recording()
        rec.cache["s2"] = Session(
            id = "s2",
            directory = "/w",
            time = Session.Time(created = 555L, updated = 556L),
        )
        val tracker = DshSessionSeqTracker()
        val dispatched = mutableListOf<SseEvent>()
        val job = launch {
            orchestrator().run(
                "http://x", source, FakeHistorySource(emptyMap()), tracker,
                dispatch = { dispatched += it },
                onEvent = {},
                sessionLookup = { rec.cache[it] },
                onConnected = {},
            )
        }
        runCurrent() // 让 launch 先执行到 start()（onFrame 就绪）
        source.onFrame(
            "host/session-added",
            obj("""{"type":"host/session-added","sessionId":"s2","blank":true,"origin":"subagent","cwd":"/w"}"""),
            "r",
        )
        runCurrent()
        val created = dispatched.filterIsInstance<SseEvent.SessionCreated>().single()
        assertEquals(555L, created.info.time.created) // 防御合并而非 epoch0
        job.cancel()
    }
}
