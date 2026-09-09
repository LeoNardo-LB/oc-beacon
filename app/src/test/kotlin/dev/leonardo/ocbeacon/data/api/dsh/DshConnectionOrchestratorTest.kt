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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        // InitialFetch：只取尾页（beforeSeq=baseline+1=21——排他游标含入 seq==lastSeq 事件，maxMessages=50）
        assertEquals(listOf(Triple("s1", 21L, 50)), history.requests)
        // 尾页两行重放为 2 条 MessageUpdated（保序）
        val updates = rec.dispatched.filterIsInstance<SseEvent.MessageUpdated>()
        assertEquals(listOf("seq-s1-15", "seq-s1-20"), updates.map { it.info.id })
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
        //（游标 13 = baseline+1，FakeHistorySource 仅录制不校验游标）
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

    /**
     * ⑥（2026-09-01 预设锁定竞态抽验）：session/title 等最小 SessionUpdated 不携带
     * agentPreset——防御必须保留缓存值（a16ee74b 同族回归防线：预设卡门控/高亮
     * 随最小更新帧丢失）。走查后修复批收口项。
     */
    @Test
    fun `minimal SessionUpdated preserves cached agentPreset`() = runTest {
        val source = FakeFrameSource()
        val rec = Recording()
        rec.cache["s1"] = Session(
            id = "s1",
            directory = "/home/user/project",
            title = "Old",
            time = Session.Time(created = 111L, updated = 999L),
            agentPreset = "cordis",
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
        runCurrent()
        // session/title 产物：不携带 agentPreset（最小 Session 形态）
        source.onFrame(
            "session/event",
            obj("""{"type":"session/event","sessionId":"s1","event":{"type":"session/title","seq":31,"time":1788109002000,"data":{"title":"New Title"}}}"""),
            "r",
        )
        runCurrent()
        val updated = rec.dispatched.filterIsInstance<SseEvent.SessionUpdated>().single()
        assertEquals("cordis", updated.info.agentPreset) // 防御：预设不随最小帧丢
        assertEquals("New Title", updated.info.title)
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

    /**
     * A2(2026-09-06 全量 E2E):activity 最小 SessionUpdated(title 缺席、created=0、
     * updated=事件时刻)经防御合并——updated 取 max(web mod04.js mutation
     * kind=activity 同款单调语义:仅当 mutation.updatedAt > summary.updatedAt 才
     * 更新),标题等元数据保留缓存不被整替换抹除;旧时刻(重放腿)不回拉排序位。
     */
    @Test
    fun `defendSessionReplacement merges activity timestamp monotonically keeping cached title`() {
        val existing = Session(id = "s1", directory = "/w", title = "T", time = Session.Time(100L, 100L))
        val newer = SseEvent.SessionUpdated(Session(id = "s1", time = Session.Time(created = 0L, updated = 900L)))
        val defendedNewer = orchestrator().defendSessionReplacement(newer) { existing } as SseEvent.SessionUpdated
        assertEquals(900L, defendedNewer.info.time.updated)
        assertEquals("T", defendedNewer.info.title)
        val older = SseEvent.SessionUpdated(Session(id = "s1", time = Session.Time(created = 0L, updated = 50L)))
        val defendedOlder = orchestrator().defendSessionReplacement(older) { existing } as SseEvent.SessionUpdated
        assertEquals(100L, defendedOlder.info.time.updated)
    }

    /**
     * #310① A8 缺陷B根因钉（第二层防线）：最小 SessionCreated/SessionUpdated
     * （host/session-added、session/title 产物）不携 parentId 时，防御合并必须保留
     * 缓存父址——否则子会话条目被整对象替换抹掉 parentId，ChatSendDelegate 续聊
     * 分流（parentId 非空）在两次发送之间失效（A8：首 发 subagents/prompt 为正 /
     * 次 发 session/prompt 被服务器拒 "owned by subagent routing"）。
     */
    @Test
    fun `defendSessionReplacement preserves cached parentId when minimal event omits it`() {
        val existing = Session(id = "s-child", directory = "/w", parentId = "s-parent", time = Session.Time(555L, 555L))
        val incoming = SseEvent.SessionUpdated(
            Session(id = "s-child", title = "t", time = Session.Time(created = 0L, updated = 9L)),
        )
        val defended = orchestrator().defendSessionReplacement(incoming) { existing } as SseEvent.SessionUpdated
        assertEquals("s-parent", defended.info.parentId)
    }

    /**
     * #310① A8轮3 根因钉（缺陷C①/C②/D 共根）：DSH host/session-removed 对 durable
     * 子会话是**轮次落定时的激活处置**（continuable 会话的 Session 在服务器持久，
     * 官方 client SessionManager.handleSessionRemoved 对 origin=subagent 行记
     * {kind:'status', running:false} 而非 {kind:'remove'}）——app 无差别映射
     * SessionDeleted 后级联清消息+删行，完结回复 seq-3502 在 9ms 内被
     * clearForSession 抹掉（a8r2.log 17:28:04.812-.814）、会话行消失致 ChatRoute
     * 落 ChatEmptyState（自发回空态 Chat）。
     */
    @Test
    fun `defendDurableSubagentRemoval downgrades SessionDeleted to idle for cached subagent row`() {
        val existing = Session(id = "s-child", directory = "/w", parentId = "s-parent", time = Session.Time(555L, 555L))
        val deleted = SseEvent.SessionDeleted(Session(id = "s-child", time = Session.Time(0L, 0L)))
        val defended = orchestrator().defendDurableSubagentRemoval(deleted) { existing }
        assertEquals(SseEvent.SessionStatus("s-child", dev.leonardo.ocbeacon.domain.model.SessionStatus.Idle), defended)
    }

    /** 普通会话（无父址）的 session-removed 是真删除——原样透传。 */
    @Test
    fun `defendDurableSubagentRemoval passes SessionDeleted through for ordinary session`() {
        val existing = Session(id = "s-top", directory = "/w", time = Session.Time(555L, 555L))
        val deleted = SseEvent.SessionDeleted(Session(id = "s-top", time = Session.Time(0L, 0L)))
        val defended = orchestrator().defendDurableSubagentRemoval(deleted) { existing }
        assertEquals(deleted, defended)
    }

    /** 缓存无行（未见过的会话）——无从判定 durable，保守透传（删除对无行会话本近 no-op）。 */
    @Test
    fun `defendDurableSubagentRemoval passes SessionDeleted through for unknown session`() {
        val deleted = SseEvent.SessionDeleted(Session(id = "s-ghost", time = Session.Time(0L, 0L)))
        val defended = orchestrator().defendDurableSubagentRemoval(deleted) { null }
        assertEquals(deleted, defended)
    }

    /** 非 SessionDeleted 事件不经本防御（防御域单职责）。 */
    @Test
    fun `defendDurableSubagentRemoval leaves non-deletion events untouched`() {
        val status = SseEvent.SessionStatus("s-child", dev.leonardo.ocbeacon.domain.model.SessionStatus.Busy)
        val defended = orchestrator().defendDurableSubagentRemoval(status) { null }
        assertEquals(status, defended)
    }

    /**
     * 帧级端到端：子会话轮次落定 → host/session-removed 到达时缓存行 parentId 非空
     * → dispatch 收到 idle 状态帧而非 SessionDeleted（行/消息级联不触发——完结回复
     * 留在转录、会话行保留、ChatRoute 不落空态）。
     */
    @Test
    fun `host session-removed for cached subagent child dispatches idle not deletion`() = runTest {
        val source = FakeFrameSource()
        val rec = Recording()
        rec.cache["s-child"] = Session(
            id = "s-child",
            directory = "/w",
            parentId = "s-parent",
            title = "Your task: count slowly",
            time = Session.Time(created = 555L, updated = 556L),
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
        source.onFrame(
            "host/session-removed",
            obj("""{"type":"host/session-removed","sessionId":"s-child"}"""),
            "r",
        )
        runCurrent()
        assertTrue(rec.dispatched.none { it is SseEvent.SessionDeleted })
        assertEquals(
            listOf(dev.leonardo.ocbeacon.domain.model.SessionStatus.Idle),
            rec.dispatched.filterIsInstance<SseEvent.SessionStatus>().map { it.status },
        )
        assertEquals(rec.dispatched, rec.notified) // onEvent 与 dispatch 同序同集
        job.cancel()
    }

    /** 对照组：普通会话的 host/session-removed 仍走 SessionDeleted（真删除语义不变）。 */
    @Test
    fun `host session-removed for ordinary session still dispatches deletion`() = runTest {
        val source = FakeFrameSource()
        val rec = Recording()
        rec.cache["s-top"] = Session(
            id = "s-top",
            directory = "/w",
            time = Session.Time(created = 555L, updated = 556L),
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
        runCurrent()
        source.onFrame(
            "host/session-removed",
            obj("""{"type":"host/session-removed","sessionId":"s-top"}"""),
            "r",
        )
        runCurrent()
        assertEquals(listOf("s-top"), rec.dispatched.filterIsInstance<SseEvent.SessionDeleted>().map { it.info.id })
        job.cancel()
    }

    // ---- followTargets：follow 限界窗口（#319）+ durable 地址（#310① A8） --------

    private fun itemOf(sid: String?, running: Boolean, updatedAt: Long?): JsonObject =
        buildJsonObject {
            sid?.let { put("sessionId", it) }
            put("running", running)
            updatedAt?.let { put("updatedAt", it) }
        }

    /** 子会话行：parentSessionId + origin + subagent 投影身份（mode）。 */
    private fun subagentItem(sid: String, mode: String?): JsonObject =
        buildJsonObject {
            put("sessionId", sid)
            put("running", true)
            put("updatedAt", 1_000_000_000_000L)
            put("parentSessionId", "s-parent")
            put("origin", "subagent")
            mode?.let {
                put("projections", buildJsonObject {
                    put("values", buildJsonObject {
                        put("subagent", buildJsonObject { put("mode", it) })
                    })
                })
            }
        }

    private val dayMs = 24L * 60 * 60 * 1000

    @Test
    fun followTargets_keepsRunningRegardlessOfAge() {
        val now = 1_000_000_000_000L
        val items = listOf(itemOf("s-run", running = true, updatedAt = now - 90 * dayMs))
        val targets = followTargets(items, now)
        assertEquals(listOf("s-run"), targets.map { it.sessionId })
        assertEquals("session", targets[0].address.strOfKey("kind"))
    }

    @Test
    fun followTargets_keepsRecentWithinWindow() {
        val now = 1_000_000_000_000L
        val items = listOf(
            itemOf("s-fresh", running = false, updatedAt = now - 12 * 60 * 60 * 1000), // 12h 前，窗口内
            itemOf("s-stale", running = false, updatedAt = now - 2 * dayMs), // 48h 前，窗外
        )
        assertEquals(listOf("s-fresh"), followTargets(items, now).map { it.sessionId })
    }

    @Test
    fun followTargets_skipsStaleBeyondWindowEvenWithSkewTolerance() {
        val now = 1_000_000_000_000L
        // 距窗口边界 +31min：超出 30min 容差不 follow；窗口内（24h-29min）follow
        val items = listOf(
            itemOf("s-edge-out", running = false, updatedAt = now - dayMs - 31 * 60 * 1000),
            itemOf("s-edge-in", running = false, updatedAt = now - dayMs + 29 * 60 * 1000),
        )
        assertEquals(listOf("s-edge-in"), followTargets(items, now).map { it.sessionId })
    }

    /**
     * #310① A8 缺陷A根因钉（follow 腿）：子会话按 durable subagent 地址开流——
     * 旧实现整体跳过（#319 时代 session/follow 恒 {kind:session} 必被服务器拒，
     * A7 logcat 6385 动态补开同样被拒），子会话转录增量 3min 恒空。
     */
    @Test
    fun followTargets_subagentChildWithMode_followsViaSubagentAddress() {
        val now = 1_000_000_000_000L
        val items = listOf(
            subagentItem("s-child", mode = "continuable"),
            itemOf("s-normal", running = true, updatedAt = now),
        )
        val targets = followTargets(items, now)
        assertEquals(listOf("s-child", "s-normal"), targets.map { it.sessionId })
        val childAddress = targets[0].address
        assertEquals("subagent", childAddress.strOfKey("kind"))
        assertEquals("s-parent", childAddress.strOfKey("parentSessionId"))
        assertEquals("s-child", childAddress.strOfKey("childSessionId"))
        assertEquals("continuable", childAddress.strOfKey("mode"))
    }

    /** subagent 投影缺席（无 mode）无法构成合法地址（validateAddress 强校验）——保守跳过。 */
    @Test
    fun followTargets_subagentChildWithoutModeProjection_skipped() {
        val items = listOf(subagentItem("s-child", mode = null))
        assertTrue(followTargets(items, 1_000_000_000_000L).isEmpty())
    }

    /** origin=subagent 却无父址的孤儿行无法 durable 寻址——跳过。 */
    @Test
    fun followTargets_subagentOrphanWithoutParent_skipped() {
        val orphan = buildJsonObject {
            put("sessionId", "s-orphan")
            put("running", true)
            put("updatedAt", 1_000_000_000_000L)
            put("origin", "subagent")
        }
        assertTrue(followTargets(listOf(orphan), 1_000_000_000_000L).isEmpty())
    }

    @Test
    fun followTargets_missingUpdatedAtTreatedAsAncient_skipped() {
        val items = listOf(
            itemOf("s-no-ts", running = false, updatedAt = null),
            itemOf(null, running = true, updatedAt = 1L), // 无 sessionId 丢弃
        )
        assertTrue(followTargets(items, 1_000_000_000_000L).isEmpty())
    }

    /** open 帧 args wire 形状钉：{request:{address}}（SessionFollowRequest）。 */
    @Test
    fun followTarget_followArgsWrapAddressInRequest() {
        val target = followTargets(listOf(subagentItem("s-child", mode = "continuable")), 1_000_000_000_000L).single()
        val args = target.followArgs()
        val request = args["request"] as? JsonObject
        val address = request?.get("address") as? JsonObject
        assertEquals("subagent", address?.strOfKey("kind"))
    }


    /** #333：fork 子会话（parentSessionId 在、origin 缺席）是普通会话——窗口内照常以
     * session 地址 follow（旧实现误判 subagent 因无 mode 投影而 null 地址跳过——fork
     * 子会话连接期从未开流）。 */
    @Test
    fun followTargets_forkChildWithoutOrigin_followsViaSessionAddress() {
        val now = 1_000_000_000_000L
        val forkChild = buildJsonObject {
            put("sessionId", "s-fork")
            put("running", false)
            put("updatedAt", now)
            put("parentSessionId", "s-parent")
        }
        val targets = followTargets(listOf(forkChild), now)
        assertEquals(listOf("s-fork"), targets.map { it.sessionId })
        assertEquals("session", targets[0].address.strOfKey("kind"))
        assertEquals("s-fork", targets[0].address.strOfKey("sessionId"))
    }

    /** #333：单条目无窗口解析（聚焦 follow 兜底）——不问 recency/running，仅拒孤儿与无法寻址行。 */
    @Test
    fun followTargetOfItem_resolvesRegardlessOfRecencyWindow() {
        val ancient = buildJsonObject {
            put("sessionId", "s-old")
            put("running", false)
            put("updatedAt", 1L)
            put("cwd", "/w")
        }
        val target = followTargetOfItem(ancient)
        assertEquals("s-old", target?.sessionId)
        assertEquals("session", target?.address?.strOfKey("kind"))
    }

    /** #333：单条目解析对 origin=subagent 孤儿行（无父址）保守拒绝。 */
    @Test
    fun followTargetOfItem_subagentOrphanWithoutParent_rejected() {
        val orphan = buildJsonObject {
            put("sessionId", "s-orphan")
            put("origin", "subagent")
            put("running", true)
        }
        assertNull(followTargetOfItem(orphan))
    }
    private fun JsonObject.strOfKey(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
}
