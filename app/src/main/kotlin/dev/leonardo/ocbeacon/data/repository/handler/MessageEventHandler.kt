package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.data.mapper.MessageMergeEngine
import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 消息和 part 数据的共享状态存储 + 5 类消息事件的分发 handler。
 *
 * 持有 `_messages`、`_parts` 和 `assistantMessageIds` 状态，这些状态在
 * 消息/part 生命周期中紧密耦合（例如 [handleMessagePartUpdated] 会查询
 * 由 [handleMessageUpdated] 填充的 `assistantMessageIds`；
 * [handleMessageUpdated] 为用户消息播种 `_parts`）。原三壳 handler
 *（MessagePart/MessageUpdated/MessageRemoved，各 ~25 行纯转发且 serverId
 * 未用）于 #175 删除——本类直接实现 [SseEventHandler]（handle 五分支）。
 *
 * SSE 双写：当 [messageStore] 非 null（生产环境 Hilt 注入）时，SSE 流式更新
 * 会异步落盘到 Room，以便离线/重启后恢复。测试环境传 null 禁用双写。
 */
@Singleton
class MessageEventHandler @Inject constructor(
    private val messageStore: MessageCacheRepository?,
) : SseEventHandler {
    /** 测试用无参构造：禁用 SSE 双写。生产环境由 Hilt 注入非空 MessageCacheRepository。 */
    constructor() : this(null)

    /**
     * 5 类消息事件的识别契约（原三壳的转发逻辑收编，#175）：
     * MessageUpdated / MessageRemoved / MessagePartUpdated / Delta / PartRemoved。
     */
    override fun handle(event: SseEvent, serverId: String): Boolean {
        val handled = when (event) {
            is SseEvent.MessageUpdated -> { handleMessageUpdated(event); true }
            is SseEvent.MessageRemoved -> { handleMessageRemoved(event); true }
            // #509：合成 id→权威 id 的原地换名（毕业换装）——行不离开列表、
            // part.id 不变，消除 remove+add 的列表成员空窗（P5-3 过滤把「权威
            // 行已到、parts 未到」的中间态整轮过滤 → turn 组瞬空 → t_ 条目销毁
            // → 全部槽位记忆归零 → asyncTerminal Loading≈0px 空白 350-700ms）。
            is SseEvent.MessageIdSwapped -> { handleMessageIdSwapped(event); true }
            is SseEvent.MessagePartUpdated -> { handleMessagePartUpdated(event); true }
            is SseEvent.MessagePartDelta -> { handleMessagePartDelta(event); true }
            is SseEvent.MessagePartRemoved -> { handleMessagePartRemoved(event); true }
            // #453：块完结时间补丁（DSH block-end）——无 kind 的终态化信号
            is SseEvent.MessagePartTimePatch -> { handleMessagePartTimePatch(event); true }
            // #378：表面区间替换（user/message surfaceOp.replace）——被遮蔽旧消息
            // 折叠的权威指令：台账记账 + 内存/热表移除（幂等，实况/历史同事件）。
            is SseEvent.SurfaceRangeReplaced -> { handleSurfaceRangeReplaced(event); true }
            else -> false
        }
        // #442 B案：SSE 结构事件统一发布结构性视图。**MessagePartDelta 例外**
        //——它仅缓冲（不立即改热视图），但 flush 与后续 delta 事件的交错会把
        //「已含本批累积」的热视图新值过桥（B6 真机定罪：CML-tick ~6/s =
        // 每 flush 后首个 delta 事件击穿结构性静默）——结构性发射只属于结构
        // 事件族。dispatch 外直调入口（upsert/clear/patch 族）各自就地发布。
        if (handled && event !is SseEvent.MessagePartDelta) publishStructural(event::class.simpleName ?: "SseEvent")
        return handled
    }

    internal companion object {
        const val TAG = "MsgEventHandler"

        /**
         * #95（H-4 泄漏）：单会话消息热视图内存上限——与 Room 侧
         * MessageStore.SESSION_MESSAGE_LIMIT（1000）对齐。超出后保留最新 N 条，
         * 被裁剪消息的 parts / assistantMessageIds 同步清理（更早历史由
         * 冷存桶 + loadAround 按需分页加载，不依赖热视图）。
         */
        internal const val MEMORY_SESSION_MESSAGE_LIMIT = 1000

        /**
         * #340：全量 upsert 合并刷洗参数——消息数阈值 / 最大时延 / 刷洗
         * 周期 tick。真机 resync 洪峰（数千事件/秒）下按 128 条或 250ms
         * 批量落库，吞吐数量级提升且不再丢弃写请求。
         */
        internal const val UPSERT_BATCH_THRESHOLD = 128
        internal const val UPSERT_BATCH_MAX_LATENCY_MS = 250L
        internal const val UPSERT_BATCH_TICK_MS = 25L
        // #437 cadence 裁决 3 收编：常量归引擎域（ScrollCompensation.STREAM_FLUSH_INTERVAL_MS）——
        // 数据层消费引擎节奏（spec「引擎接管 SSE cadence」的结构落位）
        private const val STREAM_FLUSH_INTERVAL_MS =
            dev.leonardo.ocbeacon.ui.screens.chat.components.STREAM_FLUSH_INTERVAL_MS

        /**
         * #338：历史残留 completed 的物理不可能阈值——超会话域水位此时长
         * 即判旧本地钟回填残留（实证残留 +3.5h；合法完结与水位差恒小）。
         */
        internal const val POLLUTED_COMPLETED_MARGIN_MS = 10 * 60_000L

        /**
         * #490：已拆待播台账容量上界——follow/历史回放会重放旧 user/message
         * （各携带一次 pending-* 拆除登记），FIFO 上界防无界增长；requestId
         * 每次发送新铸（UUID），淘汰永不误伤未来播种。
         */
        internal const val PRE_DEMOLISHED_ECHO_LIMIT = 32

        /**
         * #490：pending-* echo 行的合法寿命宽限。播种→拆除的正常间隔 <1s
         *（持久回显随受理即时广播）；宽限远大于该窗口只为容纳极端调度延迟，
         * 超龄即判拆除丢失（WS 断连/竞态残留/历史版本缺陷）的幽灵。
         */
        internal const val STALE_PENDING_ECHO_MS = 120_000L
    }

    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()

    private val _parts = MutableStateFlow<Map<String, List<Part>>>(emptyMap())
    val parts: StateFlow<Map<String, List<Part>>> = _parts.asStateFlow()

    // #442 B案 节奏收编（spec 2026-10-02 §2.1）：UI 主列表消费的**结构性视图**——
    // 仅结构性事件发射（part 生命周期/消息生命周期/会话清理/REST 合并）；流式
    // delta 批只进热视图（_parts）与 StreamingDeltaBus，不经此流——十源 combine
    // 及其下游（ChatScreen 投影/ChatMessageList 函数体）在流式稳态零滴答
    //（根因二：100ms 批快照重组链收口）。热视图语义零变更（所有既有读点
    //（isStaleDelta/inferDeltaKind/持久化）继续读 _parts 拿最新累积）。
    private val _structuralParts = MutableStateFlow<Map<String, List<Part>>>(emptyMap())
    val structuralParts: StateFlow<Map<String, List<Part>>> = _structuralParts.asStateFlow()

    /** 结构性发布：热视图当前值整体过桥（同实例=StateFlow 值相等去重，幂等零成本）。
     *  [cause] 仅用于 [B2-struct] 埋点动机标注（哪个结构事件触发了 combine 源滴答）。 */
    private fun publishStructural(cause: String) {
        if (!dev.leonardo.ocbeacon.ui.screens.chat.components.StreamingDeltaBus.enabled) return
        _structuralParts.value = _parts.value
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "[B2-struct] publish cause=$cause msgs=${_parts.value.size} — 结构性视图过桥（流式 delta 批不经此=根因二静默前提）")
        }
    }

    /** #442 B案：消息内已终态（time.end≠0）的 Text/Reasoning 撤销 bus 覆盖——
     *  structural 权威已发布，live 让位防陈旧覆盖（服务端改写/迟滞累积族）。 */
    private fun clearTerminalLiveParts(messageId: String) {
        if (!dev.leonardo.ocbeacon.ui.screens.chat.components.StreamingDeltaBus.enabled) return
        val parts = _parts.value[messageId] ?: return
        val terminal = parts.mapNotNull { p ->
            val ended = when (p) {
                is Part.Text -> (p.time?.end ?: 0L) != 0L
                is Part.Reasoning -> (p.time?.end ?: 0L) != 0L
                else -> false
            }
            if (ended) p.id else null
        }
        if (terminal.isNotEmpty()) {
            dev.leonardo.ocbeacon.ui.screens.chat.components.StreamingDeltaBus.clearParts(terminal)
        }
    }

    /**
     * assistant 消息 ID 集合，供 PartUpdated handler 进行快速 O(1) 查找。
     *
     * RS-009 修复：使用 ConcurrentHashMap.newKeySet() 而非 mutableSetOf()。
     * 旧的 LinkedHashSet 不是线程安全的——来自多个 SSE 服务器协程
     *（各自运行在 Dispatchers.IO 上）的并发访问可能破坏内部链表结构
     * 或导致 ConcurrentModificationException。由 ConcurrentHashMap 支持的
     * 并发键集视图提供线程安全的 add/remove/contains/clear，无需显式加锁，
     * 且迭代器是弱一致的（永不抛出 CME）。
     */
    private val assistantMessageIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // ── SSE delta 批处理（窗口 = STREAM_FLUSH_INTERVAL_MS，引擎域常量）──
    // 缓冲传入的 delta 并按批周期（100ms）刷新一次，以降低
    // 重组频率。每次 flush = 1 次 StateFlow 更新 = 1 次
    // 重组 = 1 次 layout 修饰符测量。
    private data class PendingDelta(
        val messageId: String,
        val partId: String,
        val sessionId: String,
        val delta: String,
        val type: String  // "text" 或 "reasoning"
    )

    private val batchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pendingDeltas = mutableListOf<PendingDelta>()
    private val pendingLock = Any()
    private var batchJob: Job? = null
    /** debug 级 delta flush 节流计数器（仅 DEBUG 构建使用）。 */
    private var deltaFlushCounter = 0

    // ---- 持久化 actor（#57 → #340 合并写重构）----
    // 所有 SSE 双写落盘请求由单一写协程串行处理（协程数恒为 1，
    // App 进程消亡时随进程终止）。
    //
    // #340 根因修复：原 Channel.BUFFERED(64) + trySend 满即丢——真机 resync 期
    // 实证 dropped 1150→1500 连发（Room 写入慢于 SSE 生产时丢弃最新写
    // 请求，含终态修复写）。两路重构：
    // - 增量 delta：UNLIMITED channel 保序入队不丢（流式生产速率有界：批 cadence）；
    // - 全量 upsert：按 (sessionId, messageId) 最新快照合并（latest-wins，快照语义
    //   天然幂等），内存占用=窗口内不同消息数（阈值刷洗封顶）；
    // - 刷洗策略：消息数≥阈值或 最老条目时延≥上限时刷洗（每会话
    //   单次 upsertMessages 调用=单事务）——吞吐数量级提升，不再丢写。
    private data class PersistRequest(
        val store: MessageCacheRepository,
        val sessionId: String,
        val payload: List<MessageWithParts>,
        /** #97（H-6）：非空时走增量落盘（appendPartTexts），空时全量 upsert。 */
        val incrementalDeltas: List<dev.leonardo.ocbeacon.data.local.PartDelta> = emptyList(),
    )

    /** #340：增量写保序队（UNLIMITED——流式速率有界，永不丢）。 */
    private val deltaPersistQueue = Channel<PersistRequest>(Channel.UNLIMITED)

    /** #340：写协程唤醒信号（CONFLATED 天然合并突发）。 */
    private val persistWakeups = Channel<Unit>(Channel.CONFLATED)

    /** #340：全量 upsert 合并缓冲（sessionId → messageId → 最新快照；pendingUpsertsLock 保护）。 */
    private val pendingUpserts = HashMap<String, HashMap<String, MessageWithParts>>()
    private val pendingUpsertsLock = Any()
    private var pendingUpsertCount = 0
    private var oldestPendingUpsertAt = 0L

    /**
     * #437 验收十五轮：待删行队列（sessionId → messageId 集合；与 [pendingUpserts]
     * 同锁域）。原拆除删除走旁路并行协程（batchScope=Dispatchers.Default 多线程），
     * 与合并缓冲的时延批 upsert 无顺序保证——真机 flicker3 实证：pending-* 播种
     * upsert 事务 42:57.686 才提交，拆除 delete 42:57.519 先行 → 后到 upsert 重插
     * 已删行 → 幽灵行留存热表 → REST 刷新/分页合并回灌复活为可见重复气泡
     * （u_pending-…f74 挂屏 6 分钟，1956 行日志取证）。并入单写协程后天然串行：
     * 拆除先从合并缓冲撤下未写行（重放安全），删除在既有写入之后执行——写序
     * 竞态构造性消除；同 id 再到达时 enqueueUpsert 撤销待删（事件时间最后操作胜出）。
     */
    private val pendingDeletes = HashMap<String, HashSet<String>>()

    /**
     * #490 换装握手顺序无关化：已拆待播台账（pendingId → 登记时刻）。
     *
     * DSH 0.2.0-rc.2 实测（hitl3 捕获 09-30 22:21:31）：服务器广播的持久
     * user/message 帧（mapper 随帧补发 MessageRemoved(pending-<rpcId>) 拆除）
     * 可先于 prompt RPC 的 HTTP 响应到达——拆除时刻幽灵尚未播种（无行可删
     * no-op），响应返回后的本地播种成为永不拆除的持久幽灵（单发双消息且
     * 重进仍在的根因；同捕获 22:21/23:29/23:32 三次幽灵 vs 22:34/23:19 两次
     * 正常换装 = 同一竞态的两种落序）。拆除时刻在此登记，迟到的播种命中即
     * 丢弃：无论到达顺序，「pending-* 在拆除后必不存在」恒成立。V2 通道
     * admission.id 即 durable id（同 id 幂等合并，顺序无关），不经本台账。
     */
    private val preDemolishedEchoes = LinkedHashMap<String, Long>()
    private val preDemolishedLock = Any()

    /** #490：登记一次 pending 拆除（含行在场被真删与行缺席 no-op 两种落序）。 */
    private fun recordPreDemolishedEcho(id: String) {
        synchronized(preDemolishedLock) {
            preDemolishedEchoes[id] = System.currentTimeMillis()
            while (preDemolishedEchoes.size > PRE_DEMOLISHED_ECHO_LIMIT) {
                preDemolishedEchoes.remove(preDemolishedEchoes.keys.first())
            }
        }
    }

    /** #490：播种命中已拆台账则消费并返回 true（调用方丢弃本次播种）。 */
    private fun consumePreDemolishedEcho(id: String): Boolean =
        synchronized(preDemolishedLock) { preDemolishedEchoes.remove(id) != null }

    /**
     * #338：会话时间域基准——最近观察到的该会话「消息/事件时刻」（DSH=服务器
     * 信封时刻、V2=本地构造时刻——与该会话消息 created 腿**同钟域**）。
     * [markSessionIdle] 回填 completed 时优先取该值，杜绝跨钟域回填：
     * 真机实证（2026-09-06 E2E B4）DSH 工具宿主 completed 被本地钟回填成
     * created+3.5h（resync 期回填），且 merge 语义 incoming ?: existing
     * 使污染永久残留；DSH/V1 的 created 腿为服务器时刻，设备钟慢 207ms 时
     * 流式 ticker 还会短暂显示负时长（同族症状）。
     */
    private val lastDomainEventTimeMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** #338：EventDispatcher 分发点采集（MessageUpdated.created / SessionIdle.time）。 */
    fun recordDomainTime(sessionId: String, timeMs: Long) {
        if (timeMs <= 0L) return
        lastDomainEventTimeMs.merge(sessionId, timeMs) { old, new -> maxOf(old, new) }
    }

    /** #338：域内「现在」——有基准用基准（与 created 同域），无基准回退本地钟。 */
    private fun domainNowMs(sessionId: String): Long =
        lastDomainEventTimeMs[sessionId] ?: System.currentTimeMillis()

    /**
     * #338 历史残留消毒：本事件不携带权威 completed（DSH 工具宿主整装/回放
     * 腿恒 null）而存量 completed 超出会话域水位（本事件 created 与已采集
     * 域基准的较大者）[POLLUTED_COMPLETED_MARGIN_MS] 以上——物理不可能
     * （消息不可能在会话事件流之后许久才完结）＝旧版本本地钟回填残留
     * （真机实证 completed=created+3.5h）→ 归 null（时长未知），随同点
     * 落盘修复 Room 行；下一次 resync 后旧污染自愈。
     */
    private fun sanitizeLegacyPollutedCompleted(
        sessionId: String,
        incoming: Message.Assistant,
        merged: Message.Assistant,
    ): Message.Assistant {
        if (incoming.time.completed != null) return merged
        val completed = merged.time.completed ?: return merged
        val watermark = maxOf(lastDomainEventTimeMs[sessionId] ?: 0L, incoming.time.created)
        if (completed - watermark > POLLUTED_COMPLETED_MARGIN_MS) {
            return merged.copy(time = merged.time.copy(completed = null))
        }
        return merged
    }

    init {
        // #340：单写协程——唤醒后先保序排空增量队，再按阈值/时延批量刷洗
        // 全量 upsert 合并缓冲（刷洗周期 tick 间继续排空 delta）。
        batchScope.launch {
            for (wakeup in persistWakeups) {
                drainDeltaPersistQueue()
                while (pendingUpsertCountSnapshot() > 0) {
                    val n = pendingUpsertCountSnapshot()
                    val age = oldestPendingUpsertAtSnapshot().takeIf { it > 0 }
                        ?.let { System.currentTimeMillis() - it } ?: 0L
                    if (n >= UPSERT_BATCH_THRESHOLD || age >= UPSERT_BATCH_MAX_LATENCY_MS) {
                        flushPendingUpserts()
                    } else {
                        delay(UPSERT_BATCH_TICK_MS)
                        drainDeltaPersistQueue()
                    }
                }
                // #437 十五轮：删除并入同一写协程——与既有 upsert 事务天然串行
                flushPendingDeletes()
            }
        }
    }

    /** #340：保序排空增量队（写失败静默——MessageStore 内部已捕获，内存视图不受影响）。 */
    internal suspend fun drainDeltaPersistQueue() {
        while (true) {
            val req = deltaPersistQueue.tryReceive().getOrNull() ?: break
            try {
                // #97（H-6）：增量写——只追加 delta 文本 + 骨架消息
                req.store.appendPartTexts(req.sessionId, req.payload, req.incrementalDeltas)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 写失败静默
            }
        }
    }

    /** #340：全量 upsert 入合并缓冲（同消息最新快照胜出）。 */
    private fun enqueueUpsert(store: MessageCacheRepository, sessionId: String, payload: List<MessageWithParts>) {
        synchronized(pendingUpsertsLock) {
            if (pendingUpsertCount == 0) oldestPendingUpsertAt = System.currentTimeMillis()
            val byMsg = pendingUpserts.getOrPut(sessionId) { HashMap() }
            for (mwp in payload) {
                if (byMsg.put(mwp.info.id, mwp) == null) pendingUpsertCount++
                // #437 十五轮：同 id 到达撤销待删（事件时间最后操作=upsert 胜出）
                pendingDeletes[sessionId]?.remove(mwp.info.id)
            }
        }
        persistWakeups.trySend(Unit)
    }

    /** #437 十五轮：排空待删队列（单写协程内串行——晚于本协程既有 upsert 执行）。 */
    internal suspend fun flushPendingDeletes() {
        val store = messageStore ?: return
        val batches: Map<String, List<String>>
        synchronized(pendingUpsertsLock) {
            if (pendingDeletes.isEmpty()) return
            batches = pendingDeletes.mapValues { (_, ids) -> ids.toList() }
            pendingDeletes.clear()
        }
        for ((sessionId, ids) in batches) {
            for (id in ids) {
                try {
                    store.deleteMessage(sessionId, id)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    // 写失败静默（同 persist 纪律；内存视图不受影响）
                }
            }
        }
    }

    /** #340：刷洗合并缓冲——每会话单次批量写（单事务）。 */
    internal suspend fun flushPendingUpserts() {
        val store = messageStore
        val batches: Map<String, List<MessageWithParts>>
        synchronized(pendingUpsertsLock) {
            if (pendingUpserts.isEmpty()) return
            batches = pendingUpserts.mapValues { (_, byMsg) -> byMsg.values.toList() }
            pendingUpserts.clear()
            pendingUpsertCount = 0
            oldestPendingUpsertAt = 0L
        }
        if (store == null) return
        var total = 0
        for ((sessionId, payload) in batches) {
            total += payload.size
            try {
                store.upsertMessages(sessionId, payload, persistOldBeyondWindow = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 写失败静默（内存视图不受影响；后续写补齐）
            }
        }
        if (BuildConfig.DEBUG && total >= UPSERT_BATCH_THRESHOLD) {
            AppLogger.d(TAG, "[persist] coalesced flush: sessions=" + batches.size + " msgs=" + total)
        }
    }

    private fun pendingUpsertCountSnapshot(): Int = synchronized(pendingUpsertsLock) { pendingUpsertCount }

    private fun oldestPendingUpsertAtSnapshot(): Long = synchronized(pendingUpsertsLock) { oldestPendingUpsertAt }

    private fun scheduleFlush() {
        // 不要取消进行中的定时器——那会在 token 到达速率 > 1/flush 间隔 时
        // 饿死 flush。让 delta 累积；运行中的定时器触发时会一次性 flush 它们。
        if (batchJob?.isActive == true) return
        batchJob = batchScope.launch {
            delay(streamFlushIntervalMs())
            flushPendingDeltas()
        }
    }

    /**
     * #437 高度引擎 cadence（spec 2026-09-26 裁决：引擎接管 SSE 节奏 48ms→100ms
     * tunable）。二十四世轮真机取证：48ms 批的「append→markdown 排版→cap 全子树
     * 测量→布局→配对滚动」全链成本压在单帧主线程——贴底跟随帧 p50=18ms、伴随
     * append/measure 的帧间隙 p50=72ms，即用户体感「流式高度变化一顿一顿」；
     * 间隔翻倍 → 重帧频率减半。后续单帧成本根修（测量增量化）另行批次。
     *
     * 可调（仅 DEBUG）：adb shell setprop debug.ocbeacon.streamflush <ms>（16-500）。
     */
    private fun streamFlushIntervalMs(): Long {
        // 反射读 SystemProperties 每 flush 一次无谓开销——缓存（setprop 调优本就要求重启进程）
        cachedFlushIntervalMs?.let { return it }
        val v = if (!BuildConfig.DEBUG) STREAM_FLUSH_INTERVAL_MS
        else try {
            @Suppress("PrivateApi")
            val sp = Class.forName("android.os.SystemProperties")
            (sp.getMethod("get", String::class.java).invoke(null, "debug.ocbeacon.streamflush") as? String)
                ?.toLongOrNull()?.coerceIn(16L, 500L) ?: STREAM_FLUSH_INTERVAL_MS
        } catch (_: Throwable) {
            STREAM_FLUSH_INTERVAL_MS
        }
        cachedFlushIntervalMs = v
        return v
    }
    @Volatile private var cachedFlushIntervalMs: Long? = null

    private fun flushPendingDeltas() {
        val batch: List<PendingDelta>
        synchronized(pendingLock) {
            if (pendingDeltas.isEmpty()) return
            batch = pendingDeltas.toList()
            pendingDeltas.clear()
        }
        // #265 E2E 竞态守卫（源头版）：完结权威替换（text.ended 全量值 +
        // partId 换代）之后才 flush 的滞留 delta，其内容已并入权威文本——
        // 不过滤则 applyDelta 走 idx<0 兜底新建重复 part（真机 E2E 实证：
        // 结尾句 ×2），appendPartTexts 亦落脏行。#266 收窄：包含判定只对
        // **终态** part 生效——流式期未注册 delta 与既有 part 的内容重叠
        // 可能是合法新 part 的首段（判丢弃=内容丢失），交 applyDelta 重建
        // 兜底。partId 已注册的滞留 delta 由 applyDelta 终态守卫拦截。
        fun isStaleDelta(d: PendingDelta): Boolean {
            val messageParts = _parts.value[d.messageId].orEmpty()
            if (messageParts.any { it.id == d.partId }) return false
            if (d.delta.isEmpty()) return true
            val terminalText = messageParts.filter { p ->
                val terminal = when (p) {
                    is Part.Text -> (p.time?.end ?: 0L) != 0L
                    is Part.Reasoning -> (p.time?.end ?: 0L) != 0L
                    else -> false
                }
                terminal && (if (d.type == "reasoning") p is Part.Reasoning else p is Part.Text)
            }.joinToString("") { p ->
                when (p) {
                    is Part.Text -> p.text
                    is Part.Reasoning -> p.text
                    else -> ""
                }
            }
            return terminalText.contains(d.delta)
        }
        val effective = batch.filterNot { isStaleDelta(it) }
        if (effective.isEmpty()) return
        if (BuildConfig.DEBUG) {
            // debug 级流式 flush 日志（节流：每 100 批打一次）——用于确认
            // delta 正在落库（"无回复/输出中断"排查的关键节点）。
            deltaFlushCounter++
            if (deltaFlushCounter % 100 == 1) {
                AppLogger.d(TAG, "[flush] deltas=${effective.size} (batch #${deltaFlushCounter}, first=${effective.first().messageId.take(12)})")
            }
        }

        // #501 part 出生检测基准：本批触及消息在 update 前的 part id 集。DSH
        // 线面 block-start 空种子被 #230 零信息丢弃后，part 只能由下方 applyDelta
        // idx<0 兜底在热视图出生——出生是结构事实（列表条目新增），与纯文本
        // 增长（走 bus，结构性静默）必须区分对待。
        // #513 补充基准：空种子**已注册**族（V1 part.updated 以空文本种子直接
        // 注册，#230 不弃）的首次落文本——渲染条目从「无内容可渲染」（装配/
        // PartContent 的 isNotBlank 门）到「存在」同为结构事实；不桥则该 part
        // 整个流式期无渲染条目，完结 part.updated 全文才砸出（真机三层定罪：
        // bus live 流转正常而 liveFor 订阅迟至完结才建立）。
        val busEnabled = dev.leonardo.ocbeacon.ui.screens.chat.components.StreamingDeltaBus.enabled
        val touchedMessages = effective.map { it.messageId }.toSet()
        val birthBaseline: Map<String, Set<String>> =
            if (busEnabled) {
                touchedMessages.associateWith { id ->
                    _parts.value[id]?.mapTo(mutableSetOf()) { it.id } ?: mutableSetOf()
                }
            } else emptyMap()
        val firstTextBaseline: Map<String, Set<String>> =
            if (busEnabled) {
                touchedMessages.associateWith { id ->
                    _parts.value[id]?.mapNotNullTo(mutableSetOf()) { p ->
                        val blank = when (p) {
                            is Part.Text -> p.text.isBlank()
                            is Part.Reasoning -> p.text.isBlank()
                            else -> false
                        }
                        if (blank) p.id else null
                    } ?: mutableSetOf()
                }
            } else emptyMap()

        _parts.update { current ->
            // #97（M-15）：原实现批内每 delta 都整份 Map 拷贝（updated + (...)）——
            // O(N×M)。改为一次 toMutableMap，批内按 messageId 聚合就地更新。
            val updated = current.toMutableMap()
            for (entry in effective) {
                // #234：每条目变换 = MessageMergeEngine.applyDelta 纯函数
                //（endsWith 去重 + idx<0 按 kind 重建，#223/#230 语义原样迁出）。
                updated[entry.messageId] = MessageMergeEngine.applyDelta(
                    parts = updated[entry.messageId] ?: emptyList(),
                    partId = entry.partId,
                    sessionId = entry.sessionId,
                    messageId = entry.messageId,
                    kind = entry.type,
                    delta = entry.delta,
                )
            }
            updated
        }

        // #442 B案 节奏收编：触及消息的累积全文发布引擎域快通道（键=落位
        // part.id；值与热视图同字符串实例零拷贝）——UI 消费端（PartContent 两
        // 分支）以 live 覆盖参数，重组收敛到 item 内部。本发布**替代**了
        // `_parts` 对 UI 主列表的每 flush 发射（无 part 出生时 structuralParts
        // 不动）。
        if (dev.leonardo.ocbeacon.ui.screens.chat.components.StreamingDeltaBus.enabled) {
            for (messageId in effective.map { it.messageId }.toSet()) {
                dev.leonardo.ocbeacon.ui.screens.chat.components.StreamingDeltaBus
                    .publishParts(_parts.value[messageId])
            }
            // #501 出生过桥：本批有新 part id 落位 → structuralParts 过桥一次。
            // DSH 流式期零结构事件（纯 delta 线面），出生不过桥则 B案 UI 在完结
            // assistant/message 前看不到该 part——正文整段流式期不可见、完结才
            // 整段砸出（真机定罪 2026-10-02）。出生每 part 一次（低频），纯文本
            // 增长仍只走 bus——「delta 批结构性静默」不变量不破。
            // #513（2026-10-05）：first-text 过桥——V1 空种子注册族（part.updated
            // 空文本出生、非 #230 丢弃族）首块文本落位时桥一次：渲染条目的存在
            // 性在此刻才翻转（isNotBlank 门），与 part-birth 同语义低频结构事实；
            // 其后纯文本增长仍只走 bus，静默不变量不破。
            val born = birthBaseline.any { (id, before) ->
                _parts.value[id]?.any { it.id !in before } == true
            }
            if (born) {
                publishStructural("part-birth")
            } else {
                val firstText = firstTextBaseline.any { (id, blanks) ->
                    _parts.value[id]?.any { p ->
                        p.id in blanks && when (p) {
                            is Part.Text -> p.text.isNotBlank()
                            is Part.Reasoning -> p.text.isNotBlank()
                            else -> false
                        }
                    } == true
                }
                if (firstText) publishStructural("first-text")
            }
        }

        // SSE 双写：#97（H-6）增量落盘——本批 delta 只追加到对应 part 行
        //（O(delta) 写，替代原整条消息 JSON 编码 + 全行重写）。
        // 按 (sessionId, messageId) 聚合 partId→文本（同 part 多次 delta 合并）。
        val store = messageStore ?: return
        val byMessage = effective.groupBy { it.sessionId to it.messageId }
        for ((key, deltas) in byMessage) {
            val (sessionId, messageId) = key
            // 骨架消息：内存最新元数据（增量 upsert 保证 part FK 存在）
            val msgs = _messages.value[sessionId]?.filter { it.id == messageId } ?: continue
            if (msgs.isEmpty()) continue
            // 同一 part 的多次 delta 预聚合为单次追加（写量最小化）
            val aggregated = LinkedHashMap<String, String>()
            for (d in deltas) {
                aggregated[d.partId] = (aggregated[d.partId] ?: "") + d.delta
            }
            // 从内存 parts 查每个 part 的 type（reasoning/text）——增量 UPSERT 需要
            val partsByMsg = _parts.value[messageId].orEmpty().associateBy { it.id }
            val incrementalDeltas = aggregated.map { (partId, delta) ->
                dev.leonardo.ocbeacon.data.local.PartDelta(
                    partId = partId,
                    messageId = messageId,
                    sessionId = sessionId,
                    type = if (partsByMsg[partId] is Part.Reasoning) "reasoning" else "text",
                    delta = delta,
                )
            }
            val payload = msgs.map { MessageWithParts(it, _parts.value[it.id] ?: emptyList()) }
            // #340：增量写走保序无限队（流式速率有界，永不丢）
            deltaPersistQueue.trySend(
                PersistRequest(
                    store = store,
                    sessionId = sessionId,
                    payload = payload,
                    incrementalDeltas = incrementalDeltas,
                )
            )
            persistWakeups.trySend(Unit)
        }
    }

    /** 立即刷新任何待处理的 delta（供测试使用）。 */
    internal fun forceFlushDeltas() {
        batchJob?.cancel()
        batchJob = null
        flushPendingDeltas()
    }
    // ── SSE delta 批处理结束 ────────────────────────────────────────

    internal fun handleMessageUpdated(event: SseEvent.MessageUpdated) {
        val sessionId = event.info.sessionId
        // #490：迟到播种命中已拆台账——拆除已随持久回显先行到达（durable 行
        // 在场），本次播种是竞态败者的幽灵，直接丢弃（不进内存不落 Room）。
        if (event.info.id.startsWith("pending-") && consumePreDemolishedEcho(event.info.id)) {
            if (BuildConfig.DEBUG) {
                AppLogger.w(
                    TAG,
                    "[echo-drop] pre-demolished pending echo ${event.info.id.take(24)} (durable echo won the race)",
                )
            }
            return
        }
        // #378：迟到的被遮蔽消息（older page 回放在 surfaceOp 之后到达）——台账
        // 拦截，不重加（否则压缩在翻页场景下被视觉撤销）。
        DshMessageId.seqOf(event.info.id)?.let { seq ->
            if (isShadowed(sessionId, seq)) {
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "[surface] drop shadowed msg " + event.info.id.take(12))
                }
                return
            }
        }
        if (BuildConfig.DEBUG) {
            val role = event.info.role
            val completed = (event.info as? Message.Assistant)?.time?.completed
            AppLogger.d(TAG, "[msg] MessageUpdated sid=${sessionId.take(12)} id=${event.info.id.take(16)} role=$role completed=$completed")
        }
        _messages.update { current ->
            val msgs = current[sessionId]?.toMutableList() ?: mutableListOf()
            val idx = msgs.indexOfFirst { it.id == event.info.id }
            // DIAG 清理（2026-08-10）：移除 update 内的全量 filter + 日志——
            // 每次 MessageUpdated 都 O(n) 扫描 1896 条消息（仅用于日志），
            // SSE 活跃时每秒多次 → 真机掉帧（性能根因之一）。
            if (idx >= 0) {
                // 2026-08-15 修复（统计栏丢模型/耗时）：V2 step.ended 事件映射的
                // Assistant 不含 modelId/providerId/agent（服务器契约本就没有），
                // 原实现整对象替换会抹掉 step.started 写入的模型信息（tokens 却
                // 随 step.ended 同事件写入 → "圆圈在、模型无"的不对称根因）。
                // 改为非空字段合并：incoming 缺失的元数据保留 existing。
                val existing = msgs[idx]
                msgs[idx] = if (existing is Message.Assistant && event.info is Message.Assistant) {
                    // #338：合并后过历史残留消毒（旧本地钟回填 completed 自愈）
                    sanitizeLegacyPollutedCompleted(
                        sessionId,
                        event.info,
                        MessageMergeEngine.mergeAssistantMeta(existing, event.info),
                    )
                } else {
                    event.info
                }
            } else {
                // existing 已按 created 升序——二分查找插入位置，
                // 避免全量 O(n log n) 排序（高频 MessageUpdated 事件下累积 CPU）。
                // 稳定语义：相同 created 时新元素插到末尾（与 sortBy 一致）。
                val key = event.info.time.created
                var lo = 0
                var hi = msgs.size
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (msgs[mid].time.created <= key) lo = mid + 1
                    else hi = mid
                }
                msgs.add(lo, event.info)
            }
            current + (sessionId to msgs)
        }
        if (event.info is Message.Assistant) {
            assistantMessageIds.add(event.info.id)
            // #224：V1 压缩消息 SSE 实时路径——完结的 assistant(agent=compaction)
            // 折叠为 Part.Compaction 分割线（REST 路径归一化在 EventDispatcher
            // .upsertMessages；此处覆盖 message.updated 直达的单条流）。
            val normalized = dev.leonardo.ocbeacon.data.mapper.CompactionNormalizer.normalize(
                MessageWithParts(
                    info = event.info,
                    parts = _parts.value[event.info.id].orEmpty(),
                )
            )
            if (normalized.parts != _parts.value[event.info.id].orEmpty()) {
                _parts.update { current ->
                    current + (event.info.id to normalized.parts)
                }
            }
        }
        // 若尚无 part，则从摘要文本为用户消息播种 part。
        val info = event.info
        if (info is Message.User) {
            _parts.update { current ->
                if (current.containsKey(info.id)) {
                    current
                } else {
                    val summaryText = info.summary?.body?.takeIf { it.isNotBlank() }
                        ?: info.summary?.title?.takeIf { it.isNotBlank() }
                    if (summaryText != null) {
                        current + (info.id to listOf(Part.Text(
                            id = "${info.id}_summary",
                            sessionId = sessionId,
                            messageId = info.id,
                            text = summaryText
                        )))
                    } else {
                        current
                    }
                }
            }
        }
        // SSE 双写：消息元数据更新（新建/状态变更）→ 异步落盘到 Room
        persistSseUpdate(sessionId, listOf(event.info.id))
        // #95：消息插入后应用热视图上限（未超限 O(1)）——放最后：
        // 落盘先于裁剪，Room 保留全量（内存热视图才是被裁对象）
        applyMessageCap(sessionId)
    }

    /**
     * 2026-08-16 修复（F4 回复不可见 R1——孤儿 part 自愈）：
     *
     * 根因：V2 SSE 契约不发 message.updated，assistant 消息的唯一播种入口是
     * session.step.started。该事件丢失（SSE 断连窗口/字段解析失败）时，后续
     * reasoning/text/tool part 事件仍会到达——parts 写入 [_parts] 但
     * [_messages] 无宿主消息 →「有 part 无消息」→ UI 按 _messages 渲染 →
     * 用户看到"发送成功但无回复"（重进会话由 REST 恢复才可见）。
     *
     * 自愈：part/delta 写入前检查宿主存在性，缺失则以事件携带的
     * sessionId+messageId 构造骨架 Assistant（time.created=now，agent/model
     * =null——后续 step.ended/REST 兜底经 mergeAssistantMeta 非空字段合并
     * 补齐元数据），二分插入保持 created 升序（与 handleMessageUpdated 一致）。
     *
     * 幂等：双检查（O(1) assistantMessageIds 快路径 + update 内二次确认）。
     */
    internal fun ensureAssistantSkeleton(sessionId: String, messageId: String) {
        // O(1) 快路径：宿主已播种（step.started/REST/skeleton 曾写入）
        if (messageId in assistantMessageIds) return
        // 兜底线性检查：消息存在但不在集合（如 User 消息——part 宿主理论恒为
        // assistant，此分支防同 id 冲突时误插骨架）
        if (_messages.value[sessionId]?.any { it.id == messageId } == true) return
        _messages.update { current ->
            val msgs = current[sessionId]?.toMutableList() ?: mutableListOf()
            // update 内二次确认（CAS 重试/并发骨架竞争窗口）
            if (msgs.any { it.id == messageId }) return@update current
            val skeleton = Message.Assistant(
                id = messageId,
                sessionId = sessionId,
                time = TimeInfo(created = System.currentTimeMillis()),
                parentId = ""
            )
            // 二分插入保持 created 升序（combine flow 依赖写入路径有序）
            val key = skeleton.time.created
            var lo = 0
            var hi = msgs.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (msgs[mid].time.created <= key) lo = mid + 1 else hi = mid
            }
            msgs.add(lo, skeleton)
            current + (sessionId to msgs)
        }
        assistantMessageIds.add(messageId)
        AppLogger.w(
            TAG,
            "[skeleton] orphan part host missing -> seeded assistant skeleton sid=${sessionId.take(12)} msg=${messageId.take(16)} (step.started 丢失自愈)"
        )
    }

    /**
     * 2026-08-15：Assistant 消息非空字段合并（统计栏丢模型/耗时修复）。
     *
     * V2 SSE 的 step.ended 事件不含 modelId/providerId/agent（服务器契约就没有），
     * 但携带 tokens/cost；step.started 相反（带模型信息、不带 tokens）。原实现
     * 整对象替换会让两个事件互相抹掉（tokens 在而模型无的不对称）。合并规则：
     * - incoming 非空的字段以 incoming 为准（REST 权威数据可覆盖 SSE 估计值）
     * - incoming 为空的字段保留 existing（step.ended 不抹 step.started 的模型）
     * - time.created 取较早值：step.ended 映射用本地当前时刻，晚于 step.started
     *   的原始时刻——顶替会让单步消息耗时 ≈ 0 → 统计栏耗时被 `>0` 门隐藏
     * - time.completed 以 incoming 非空为准（V2 SSE 从不携带，由 markSessionIdle
     *   或 REST 兜底补齐）
     */
    private fun mergeAssistantMeta(existing: Message.Assistant, incoming: Message.Assistant): Message.Assistant =
        incoming.copy(
            modelId = incoming.modelId ?: existing.modelId,
            providerId = incoming.providerId ?: existing.providerId,
            agent = incoming.agent ?: existing.agent,
            mode = incoming.mode ?: existing.mode,
            parentId = incoming.parentId.ifBlank { existing.parentId },
            cost = incoming.cost ?: existing.cost,
            tokens = incoming.tokens ?: existing.tokens,
            finish = incoming.finish ?: existing.finish,
            time = incoming.time.copy(
                created = minOf(existing.time.created, incoming.time.created),
                completed = incoming.time.completed ?: existing.time.completed
            )
        )

    /**
     * SSE 双写辅助：将指定 sessionId 下的消息（含 parts）异步落盘到 Room。
     *
     * - fire-and-forget：在 [batchScope] 中 launch，不阻塞 SSE 处理
     * - 写失败静默（MessageStore 内部已捕获，内存视图不受影响）
     * - [messageStore] 为 null 时（测试环境）直接返回
     * - 沿用批处理节奏（STREAM_FLUSH_INTERVAL_MS）：调用方在 flushPendingDeltas（已聚合）或
     *   handleMessageUpdated（单条事件）处调用，不逐 delta 写
     */
    private fun persistSseUpdate(sessionId: String, messageIds: List<String>) {
        val store = messageStore ?: return
        if (messageIds.isEmpty()) return
        // #134（D2-L62）：_messages/_parts 为两个独立 StateFlow，无法一次原子读取
        // 两份快照；固定读取顺序（先 messages 后 parts）并把不一致的残余影响
        // 交给落盘侧兜底——appendPartText 已幂等去重（全量快照与增量 append
        // 并发交错时不会重复追加），最坏情况是快照落后一拍，下批 flush 收敛。
        val msgs = _messages.value[sessionId]?.filter { it.id in messageIds } ?: return
        if (msgs.isEmpty()) return
        val parts = _parts.value
        val payload = msgs.map { MessageWithParts(it, parts[it.id] ?: emptyList()) }
        // #340：全量写入合并缓冲（同消息最新快照胜出；单写协程批量刷洗）
        enqueueUpsert(store, sessionId, payload)
    }

    /**
     * 从缓存中移除 id >= [revertMessageId] 的消息。
     * 由 [EventDispatcher.clearRevert] 调用，防止已撤销的消息
     * 在撤销过滤器清除时短暂重现。
     */
    fun pruneRevertedMessages(sessionId: String, revertMessageId: String) {
        val removedIds = _messages.value[sessionId]
            ?.filter { it.id >= revertMessageId }
            ?.map { it.id }
            ?.toSet()
            ?: return
        if (removedIds.isEmpty()) return

        _messages.update { current ->
            val sessionMessages = current[sessionId] ?: return@update current
            current + (sessionId to sessionMessages.filter { it.id < revertMessageId })
        }
        // #442 B案：bus 清理先于热视图移除（part ids 仅此刻可得）
        dev.leonardo.ocbeacon.ui.screens.chat.components.StreamingDeltaBus
            .clearParts(_parts.value.filterKeys { it in removedIds }.values.flatten().map { it.id })
        _parts.update { it.filterKeys { msgId -> msgId !in removedIds } }
        assistantMessageIds.removeAll(removedIds)
        publishStructural("pruneReverted")

        if (BuildConfig.DEBUG) AppLogger.d(TAG, "Pruned ${removedIds.size} reverted messages for session ${sessionId.take(12)}")
    }

    internal fun handleMessageRemoved(event: SseEvent.MessageRemoved) {
        // #490：pending 拆除时刻登记（行在场=正常换装后防复活兜底；行缺席=
        // 播种后到的竞态，台账使迟到的播种在 handleMessageUpdated 处被丢弃）。
        if (event.messageId.startsWith("pending-")) {
            recordPreDemolishedEcho(event.messageId)
        }
        // bus 清理先于热视图移除（part ids 仅此刻可得——pruneReverted 同款；
        // 2026-10-03 审计 Gap A 补口：不清则 live 覆盖残留到会话级清理）
        dev.leonardo.ocbeacon.ui.screens.chat.components.StreamingDeltaBus
            .clearParts(_parts.value[event.messageId].orEmpty().map { it.id })
        _messages.update { current ->
            val sessionMessages = current[event.sessionId]?.filter { it.id != event.messageId }
            if (sessionMessages != null) current + (event.sessionId to sessionMessages) else current
        }
        _parts.update { it - event.messageId }
        assistantMessageIds.remove(event.messageId)
        // 四层根修（2026-09-09）：Room 行同删——echo 拆除此前只清内存，pending-*
        // 幽灵行留存热表，任何 Room 回灌都会复活（实测：压缩后幽灵气泡重回 UI、
        // 快速定位列出不可跳转条目）。
        // #437 验收十五轮（写序根修）：原 batchScope 并行 launch 删除与合并缓冲的
        // 时延批 upsert 无顺序保证（真机：upsert 事务晚 167ms 提交重插已删行 → 幽灵
        // 复活挂屏 6 分钟）。改记入待删队列：先从合并缓冲撤下未写行（该行从未落库），
        // 删除由单写协程在既有写入之后串行执行。
        withdrawPendingUpsertAndEnqueueDelete(event.sessionId, event.messageId)
        persistWakeups.trySend(Unit)
    }

    /**
     * #437 写序原语（handleMessageRemoved / #509 handleMessageIdSwapped 共用）：
     * 合并缓冲撤下 [messageId] 的未写快照（该行从未落库）+ 待删队列登记（删除由
     * 单写协程在既有写入之后串行执行——后到 upsert 不得重插已删行）。
     */
    private fun withdrawPendingUpsertAndEnqueueDelete(sessionId: String, messageId: String) {
        synchronized(pendingUpsertsLock) {
            val byMsg = pendingUpserts[sessionId]
            if (byMsg?.remove(messageId) != null && pendingUpsertCount > 0) {
                pendingUpsertCount--
                if (pendingUpsertCount == 0) oldestPendingUpsertAt = 0L
            }
            pendingDeletes.getOrPut(sessionId) { HashSet() }.add(messageId)
        }
    }

    /**
     * #509：消息 id 原地换名（[SseEvent.MessageIdSwapped]——DSH 毕业换装）。
     *
     * 合成 id（流式宿主 `dsh-t{turn}s{step}` / 乐观播种 `pending-<rpcId>`）与权威
     * id（`seq-…`）是同一逻辑消息的两个 wire 拼法。旧路径把它们表达为
     * MessageRemoved(合成) + MessageUpdated(权威)：两次独立 StateFlow 更新之间
     * 存在「权威行在场、parts 未到」的中间态——P5-3 过滤把该中间态整轮过滤 →
     * turn 组瞬时为空 → t_ 条目从列表消失又重现 → LazyColumn 销毁重建条目子树
     * → 全部组合内记忆（pilotEverRendered/async 终态/预解析消费门）归零 →
     * 新树 asyncTerminal Loading≈0px 空白 350-700ms（表格轮真机定罪）。
     *
     * 本路径在同一 handler 调用内**背靠背同步**完成改名（消息行原位换 id + parts 键
     * 换名 + [Part.rekeyed] 改写归属，part.id 不动；两次 StateFlow 写之间无挂起点
     * ——观察者经 dispatcher 派发恢复，只见终态），零列表成员空窗；part id 跨毕业
     * 连续使预解析注册表/分片账本/换装指纹全部免失键。
     *
     * 幂等与边界：
     * - fromId 行缺席（历史 fold 无 chunk 播种 / 重入已权威 / 重复事件）→ no-op；
     * - toId 行已在场（resync 双源：Room 已按权威 id 播种 + 实况重放又建宿主行）
     *   → 并入语义：fromId 行撤下、parts 归并（等价旧 remove+add 终态，无空窗）；
     * - pending-* fromId 无条件登记 #490 台账（换名即拆除——行缺席的竞态败者
     *   播种随后到达时被 handleMessageUpdated 丢弃）；
     * - toId 命中 #378 遮蔽区间（迟到的被压缩消息毕业）→ 跳过（紧随的
     *   MessageUpdated 由台账拦截，不重加）。
     */
    internal fun handleMessageIdSwapped(event: SseEvent.MessageIdSwapped) {
        val sessionId = event.sessionId
        // #490：pending-* 换名=拆除（含行缺席 no-op 的竞态落序——无条件登记）
        if (event.fromId.startsWith("pending-")) {
            recordPreDemolishedEcho(event.fromId)
        }
        // #378：换入目标被表面折叠遮蔽——不换名（防迟到的被压缩消息借毕业回魂）
        val toSeq = DshMessageId.seqOf(event.toId)
        if (toSeq != null && isShadowed(sessionId, toSeq)) {
            if (BuildConfig.DEBUG) {
                AppLogger.d(TAG, "[swap] drop shadowed target " + event.toId.take(16))
            }
            return
        }
        // 48ms 批窗内滞留的 fromId delta：换名后 _parts[fromId] 已撤，flush 的
        // idx<0 兜底会在旧键下重建孤儿 part——就地丢弃（其后紧随的权威终态
        // part 全文必含其内容，#265 守卫同语义）。
        synchronized(pendingLock) {
            if (pendingDeltas.isNotEmpty()) {
                pendingDeltas.removeAll { it.messageId == event.fromId }
            }
        }
        var applied = false
        var renamedIsAssistant = false
        _messages.update { current ->
            val msgs = current[sessionId]?.toMutableList() ?: return@update current
            val fromIdx = msgs.indexOfFirst { it.id == event.fromId }
            if (fromIdx < 0) return@update current  // 幂等 no-op（已换/从未在场）
            if (msgs.any { it.id == event.toId }) {
                // 双源并入：toId 行保留（Room 权威种子），fromId 行撤下
                msgs.removeAt(fromIdx)
            } else {
                val row = msgs[fromIdx]
                msgs[fromIdx] = when (row) {
                    is Message.User -> row.copy(id = event.toId)
                    is Message.Assistant -> row.copy(id = event.toId)
                    else -> return@update current  // 未建模形态不换名（防御）
                }
                renamedIsAssistant = row is Message.Assistant
            }
            applied = true
            current + (sessionId to msgs)
        }
        if (!applied) return
        _parts.update { current ->
            val fromParts = current[event.fromId] ?: return@update current
            val rekeyed = fromParts.map { it.rekeyed(event.toId) }
            val existingTo = current[event.toId].orEmpty()
            val merged = if (existingTo.isEmpty()) rekeyed
            else MessageMergeEngine.mergePartsList(existingTo, rekeyed)
            (current - event.fromId) + (event.toId to merged)
        }
        assistantMessageIds.remove(event.fromId)
        // 双源并入分支的 toId 行已由其自身 handleMessageUpdated 注册过集合；纯换名
        // 分支补注册换名后的 Assistant。
        if (renamedIsAssistant) assistantMessageIds.add(event.toId)
        // Room 写序（同 handleMessageRemoved）：fromId 撤缓冲 + 待删；换名后的行随
        // persistSseUpdate 以 toId 落盘。
        withdrawPendingUpsertAndEnqueueDelete(sessionId, event.fromId)
        persistSseUpdate(sessionId, listOf(event.toId))
        persistWakeups.trySend(Unit)
        if (BuildConfig.DEBUG) {
            AppLogger.w(
                TAG,
                "[swap] " + event.fromId.take(16) + " -> " + event.toId.takeLast(12) +
                    " parts=" + _parts.value[event.toId]?.size + " (#509 原地换名)",
            )
        }
    }

    // ============ #378 表面区间折叠（surfaceOp.replace 消费面） ============

    /**
     * 被遮蔽表面区间台账（sessionId → 闭区间列表）。来源 = SurfaceRangeReplaced
     * 事件（实况/历史 dispatch 同路径）；内存态，进程内跨页持久（older page 回放
     * 防护），重进由最新窗 surfaceOp 重播种（翻页向旧推进时先见 surfaceOp 后见
     * 遮蔽消息，天然满足）。读侧查询：[isShadowed] / [shadowedRanges]。
     */
    private val shadowedRanges =
        java.util.concurrent.ConcurrentHashMap<String, List<LongRange>>()

    /** #378：遮蔽区间的响应式镜像（UI 读侧抑制订阅；写点仅 [handleSurfaceRangeReplaced]）。 */
    private val _shadowedRangesFlow = MutableStateFlow<Map<String, List<LongRange>>>(emptyMap())
    val shadowedRangesFlow: StateFlow<Map<String, List<LongRange>>> = _shadowedRangesFlow.asStateFlow()

    /** #378：区间是否被任一已记账的折叠遮蔽（O(区间数)，每会话个位数）。 */
    fun isShadowed(sessionId: String, seq: Long): Boolean =
        shadowedRanges[sessionId]?.any { seq in it } == true

    /** #378：该会话已记账的遮蔽区间快照（UI 读侧抑制/仓储页过滤共用）。 */
    fun shadowedRanges(sessionId: String): List<LongRange> = shadowedRanges[sessionId].orEmpty()

    /**
     * #378：user/message surfaceOp.replace 到达——seq ∈ [startSeq, endSeq] 的
     * 表面消息由 [SseEvent.SurfaceRangeReplaced.byMessageId] 取代。
     *
     * 三动作（幂等，重放安全）：
     * 1. 台账记账（拦截后续迟到 MessageUpdated/Part 重加——历史 older page 在
     *    surfaceOp 之后到达的场景）；
     * 2. 内存移除（消息 + parts + assistant 索引）；
     * 3. 热表全量替换（replaceSessionMessages——#224 同款「消除本地幽灵消息」
     *    原语；同时净化 #340 合并刷洗缓冲中该会话的待写快照，防迟到 flush 复活）。
     * 冷存桶（更早历史）不动——读侧抑制（UI 归并按台账过滤）统一兜住。
     */
    internal fun handleSurfaceRangeReplaced(event: SseEvent.SurfaceRangeReplaced) {
        val range = LongRange(event.startSeq, event.endSeq)
        var flowDirty = false
        shadowedRanges.compute(event.sessionId) { _, existing ->
            if (existing != null && range in existing) {
                existing
            } else {
                flowDirty = true
                val merged = mutableListOf<LongRange>()
                if (existing != null) merged.addAll(existing)
                merged.add(range)
                merged.toList()
            }
        }
        if (flowDirty) {
            _shadowedRangesFlow.update { it + (event.sessionId to shadowedRanges[event.sessionId].orEmpty()) }
        }
        val removedIds = _messages.value[event.sessionId]
            ?.filter { DshMessageId.seqOf(it.id)?.let { s -> s in range } == true }
            ?.map { it.id }
            .orEmpty()
        if (removedIds.isNotEmpty()) {
            _messages.update { current ->
                val kept = current[event.sessionId]?.filter { it.id !in removedIds } ?: return@update current
                current + (event.sessionId to kept)
            }
            _parts.update { it.filterKeys { id -> id !in removedIds } }
            assistantMessageIds.removeAll(removedIds)
            purgePendingUpserts(event.sessionId, removedIds.toSet())
            if (BuildConfig.DEBUG) {
                AppLogger.d(TAG, "[surface] replaced seq " + event.startSeq + ".." + event.endSeq + " by " + event.byMessageId.take(12) + ": removed " + removedIds.size + " msgs")
            }
        }
        val store = messageStore ?: return
        val payload = _messages.value[event.sessionId].orEmpty().map {
            MessageWithParts(it, _parts.value[it.id].orEmpty())
        }
        batchScope.launch {
            runCatching { store.replaceSessionMessages(event.sessionId, payload) }
                .onFailure { AppLogger.w(TAG, "[surface] persist replacement failed: " + it.message) }
        }
    }

    /** #378：从 #340 合并刷洗缓冲剔除已遮蔽消息（防迟到 flush 复活幽灵行）。 */
    private fun purgePendingUpserts(sessionId: String, removedIds: Set<String>) {
        if (removedIds.isEmpty()) return
        synchronized(pendingUpsertsLock) {
            pendingUpserts[sessionId]?.let { buf ->
                val before = buf.size
                buf.keys.removeAll(removedIds)
                pendingUpsertCount -= before - buf.size
            }
        }
    }

    /**
     * #216：把 subagent 子智能体会话 ID 跨写进消息流的 Part.Tool（Running 态）。
     *
     * 根因：V2 SSE 实时链路 session.tool.called/input.ended 建 Running 态不带
     * metadata（V2SseMapper:315-330），子会话 ID 只在 .next 的 tool.progress
     * metadata 里（只喂了 activeToolProgress 进度流）——主对话流 TaskToolCard
     * 的「进行中跳转箭头」因此缺失，直到 tool.success 终态才出现。
     * 由 EventDispatcher 跨 handler 调用（同 SessionDeleted 级联模式）。
     *
     * 语义：仅补 Running 态且 metadata 缺失该 id 的 part（幂等）；
     * sessionId/sessionID 双写（childSessionIdOf 归一约定）；Completed/Error
     * 终态自带 metadata 不动。Room 双写不在此路径——重进会话时 REST 快照
     * （V2Mappers:487 Running 带全 metadata）自然补齐，冷数据无缺口。
     */
    /**
     * #287：附件 data URL 回填（Part.File.url 原位更新，幂等——url 已非空跳过）。
     * 拉取由 ChatViewModel 驱动（见 collectPendingAttachmentFetches）；此处仅
     * 热视图补写，Room 落盘随下次全量 upsert 收敛。
     * #295 勘误（2026-09-01）：_parts 是 **messageId 键**（见 applyMessageCap/
     * upsert 各读点）——原实现以 sessionId 索引恒 null、补写从未生效。改为全表
     * 按 partId 匹配（part id 全局唯一：seq-/dsh- 前缀携带会话命名空间）；
     * [sessionId] 参数保留（调用方语义与日志定位用）。
     */
    fun patchFileUrl(sessionId: String, partId: String, url: String) {
        _parts.update { current ->
            var mutated = false
            val next = current.mapValues { (_, parts) ->
                parts.map { part ->
                    if (part is Part.File && part.id == partId && part.url == null) {
                        mutated = true
                        part.copy(url = url)
                    } else part
                }
            }
            if (mutated) next else current
        }
        publishStructural("patchFileUrl")
    }

    internal fun patchToolChildSession(sessionId: String, callId: String, childSessionId: String) {
        if (childSessionId.isBlank()) return
        _parts.update { current ->
            var mutated = false
            val next = current.mapValues { (_, parts) ->
                parts.map { part ->
                    if (part is Part.Tool && part.callId == callId && part.sessionId == sessionId) {
                        when (val st = part.state) {
                            is ToolState.Running -> {
                                val md = st.metadata ?: emptyMap()
                                val has = md["sessionID"]?.let { (it as? JsonPrimitive)?.content } != null ||
                                    md["sessionId"]?.let { (it as? JsonPrimitive)?.content } != null
                                if (!has) {
                                    mutated = true
                                    val sid = JsonPrimitive(childSessionId)
                                    part.copy(state = st.copy(metadata = md + mapOf("sessionId" to sid, "sessionID" to sid)))
                                } else part
                            }
                            else -> part // Completed/Error 终态自带 metadata；Pending 无 metadata 槽
                        }
                    } else part
                }
            }
            if (mutated && BuildConfig.DEBUG) {
                AppLogger.d(TAG, "[#216] patched childSession into Running tool part callId=" + callId.take(12))
            }
            next
        }
        publishStructural("patchToolChild")
    }

    internal fun handleMessagePartUpdated(event: SseEvent.MessagePartUpdated) {
        // 2026-08-16 修复（F4 回复不可见 R1）：part 宿主消息缺失时播种骨架
        // Assistant——V2 契约中 assistant 消息唯一播种入口是 session.step.started，
        // 该事件丢失/解析失败时后续 part 事件成为"孤儿"（_parts 有数据但
        // _messages 无宿主 → UI 按 _messages 渲染 → 回复整体不可见）。
        ensureAssistantSkeleton(event.part.sessionId, event.part.messageId)
        val messageId = event.part.messageId
        _parts.update { current ->
            val messageParts = current[messageId]?.toMutableList() ?: mutableListOf()
            // #234 战役二：注册决策树收编 MessageMergeEngine.resolvePartRegistration
            //（#87b 内容匹配 / #223 同 kind 空 started 丢弃 / #230 首个空不注册 /
            //  Add 保文本——四分支语义与注释见引擎侧 KDoc）。
            when (val decision = MessageMergeEngine.resolvePartRegistration(messageParts, event.part)) {
                is MessageMergeEngine.PartRegistration.MergeAt ->
                    messageParts[decision.index] =
                        MessageMergeEngine.mergePart(messageParts[decision.index], event.part)
                is MessageMergeEngine.PartRegistration.MergeByContent ->
                    messageParts[decision.index] =
                        MessageMergeEngine.mergePart(messageParts[decision.index], event.part)
                MessageMergeEngine.PartRegistration.DropZeroInfoDuplicate,
                MessageMergeEngine.PartRegistration.DropZeroInfo -> Unit  // 零信息 part 不注册
                is MessageMergeEngine.PartRegistration.Add -> messageParts.add(decision.part)
            }
            current + (messageId to messageParts)
        }
        // #442 B案：终态（time.end）Text/Reasoning 撤销 bus 覆盖——完结权威
        //（本 update 已携全量累积）经 structuralParts 发布（dispatch 尾），
        // live 让位防陈旧覆盖。
        clearTerminalLiveParts(messageId)
    }

    /**
     * O(n+m) 两路归并——#234 战役一起，实现迁 MessageMergeEngine.mergeSortedMessages，
     * 此处保留 internal 薄委托（既有测试 MessageEventHandlerMergeSortedTest 直调 + 类内三处调用）。
     * 完整语义与迁移历史（Bug 1/2 修复、distinctBy+稳定排序等价契约）见引擎侧 KDoc。
     */
    internal fun mergeSortedMessages(
        existing: List<Message>,
        incomingSorted: List<Message>,
        merge: (existingMsg: Message, incomingMsg: Message) -> Message,
    ): List<Message> = MessageMergeEngine.mergeSortedMessages(existing, incomingSorted, merge)

    internal fun handleMessagePartDelta(event: SseEvent.MessagePartDelta) {
        // 2026-08-16 修复（F4 回复不可见 R1）：同 handleMessagePartUpdated——
        // delta 流宿主缺失时播种骨架（骨架经 mergeAssistantMeta 由后续
        // step.ended/REST 兜底补齐 agent/model 元数据）。
        ensureAssistantSkeleton(event.sessionId, event.messageId)
        // 缓冲 delta 以批量 flush（批窗口 = STREAM_FLUSH_INTERVAL_MS）——将重组频率
        // 从逐 token 降至约 10 次/秒，消除布局抖动。
        // #230：part 未注册时（空 started 被 #230 丢弃/事件丢失）此前默认
        // "text"——reasoning delta 会以正文 kind 重建（渲染进正文块+dedup
        // 分桶错乱）。按派生 id 契约判型：`_reasoning_ord_` → reasoning。
        // #234：kind 推断下沉 MessageMergeEngine.inferDeltaKind（纯函数，#230 语义）。
        val partType = MessageMergeEngine.inferDeltaKind(_parts.value[event.messageId], event.partId)
        synchronized(pendingLock) {
            pendingDeltas.add(PendingDelta(
                messageId = event.messageId,
                partId = event.partId,
                sessionId = event.sessionId,
                delta = event.delta,
                type = partType
            ))
        }
        scheduleFlush()
    }

    internal fun handleMessagePartRemoved(event: SseEvent.MessagePartRemoved) {
        _parts.update { current ->
            val messageParts = current[event.messageId]?.filter { it.id != event.partId }
            if (messageParts != null) current + (event.messageId to messageParts) else current
        }
        // #442 B案：移除的 part 撤销 bus 覆盖
        dev.leonardo.ocbeacon.ui.screens.chat.components.StreamingDeltaBus.clearPart(event.partId)
    }

    /**
     * #453：DSH block-end 的块完结时间补丁——按 `_ord_{ordinal}` 后缀扫描定位
     * （不分 kind：block-end 帧无 blockType，kind 编码的派生 id 无法单侧构造）。
     *
     * 只补 time.end == null 的流式 Text/Reasoning part（已终态的幂等跳过——
     * 流式时序上 block-end 只针对当前活动块，跨 kind 同 ordinal 的历史块早已
     * 终态化，不误伤）。end 与 start 同域钳制（chunk 信封时刻同域，防御性
     * maxOf——负跨度由显示层按未知处理，与 markSessionIdle 同口径）。
     */
    internal fun handleMessagePartTimePatch(event: SseEvent.MessagePartTimePatch) {
        val suffix = "_ord_" + event.ordinal
        var changed = false
        _parts.update { current ->
            val messageParts = current[event.messageId] ?: return@update current
            val updatedParts = messageParts.map { part ->
                when {
                    part is Part.Text && part.time?.end == null && part.id.endsWith(suffix) -> {
                        changed = true
                        val start = part.time?.start?.takeIf { it > 0 } ?: 0L
                        part.copy(time = Part.Text.Time(
                            start = start.takeIf { it > 0 } ?: event.endMs,
                            end = maxOf(event.endMs, start),
                        ))
                    }
                    part is Part.Reasoning && part.time?.end == null && part.id.endsWith(suffix) -> {
                        changed = true
                        // #263 round2 同款哨兵：start 未知（0）不伪造 start=end——
                        // 显示层走本地冻结实测时长，不显示伪造 0ms。
                        val start = part.time?.start?.takeIf { it > 0 } ?: 0L
                        part.copy(time = Part.Reasoning.Time(
                            start = start,
                            end = maxOf(event.endMs, start),
                        ))
                    }
                    else -> part
                }
            }
            if (changed) current + (event.messageId to updatedParts) else current
        }
        if (changed) {
            // 落盘闭环：重启/离线 seed 后计时冻结不回涨（对齐 markSessionIdle 的
            // persistSseUpdate 语义——内存态 part 变更必须同步 Room）。
            persistSseUpdate(event.sessionId, listOf(event.messageId))
            // #442 B案：块完结=终态，撤销该消息内 bus 终态覆盖
            clearTerminalLiveParts(event.messageId)
        }
    }

    // ============ 统一合并入口 ============

    /**
     * 统一的批量消息合并入口。三策略对应原三方法的逐语义提炼，
     * 保证 [setMessages]/[mergeMessages]/[replaceMessages]（薄委托）行为不变。
     *
     * SSE 双写：当 [messageStore] 非 null 时，合并完成后异步落盘到 Room
     *（fire-and-forget，写失败在 MessageStore 内部静默）。
     */
    fun upsertMessages(
        sessionId: String,
        incoming: List<MessageWithParts>,
        strategy: MergeStrategy,
    ) {
        when (strategy) {
            MergeStrategy.SSE_PRIORITY -> upsertSsePriority(sessionId, incoming)
            MergeStrategy.REST_AUTHORITY -> upsertRestAuthority(sessionId, incoming)
            MergeStrategy.APPEND_ONLY -> upsertAppendOnly(sessionId, incoming)
        }
        sweepStalePendingEchoes(sessionId)
        applyMessageCap(sessionId)
        // #442 B案：dispatch 外直调入口（REST 合并/缓存种子）就地发布结构性视图；
        // bus 对触及消息撤销覆盖——服务端权威若与累积分歧（resync 改写族），
        // pilot 前缀差分自证走 #472 宽限+重建兜底；流仍在飞则下一 flush 重新
        // 发布合并后基线（R6）。
        publishStructural("upsert:" + strategy::class.simpleName)
        dev.leonardo.ocbeacon.ui.screens.chat.components.StreamingDeltaBus
            .clearParts(incoming.flatMap { mwp -> mwp.parts.map { it.id } })
    }

    /**
     * #490 存量幽灵自愈：REST 快照（任何策略）不含 pending-* 行——它只在本
     * 进程播种、合法寿命 <1s（拆除随持久回显即时到达）。刷新时仍在场且超
     * [STALE_PENDING_ECHO_MS] 宽限的 pending-* 行 = 拆除丢失的幽灵（WS 断连
     * 窗口/历史版本竞态残留在 Room 的存量）——复用拆除原语（handleMessageRemoved：
     * 内存三清 + Room 待删队列单写协程路径）清淤，重进会话时 REST 首刷即愈。
     */
    private fun sweepStalePendingEchoes(sessionId: String) {
        val now = System.currentTimeMillis()
        val staleIds = _messages.value[sessionId]
            ?.filter { it.id.startsWith("pending-") && it is Message.User &&
                now - it.time.created > STALE_PENDING_ECHO_MS }
            ?.map { it.id }
            .orEmpty()
        if (staleIds.isEmpty()) return
        staleIds.forEach { id ->
            handleMessageRemoved(SseEvent.MessageRemoved(sessionId = sessionId, messageId = id))
        }
        if (BuildConfig.DEBUG) {
            AppLogger.w(TAG, "[echo-sweep] dropped ${staleIds.size} stale pending echo row(s) in ${sessionId.take(12)}")
        }
    }

    /**
     * #95（H-4 泄漏）：热视图按会话保留最新 [MEMORY_SESSION_MESSAGE_LIMIT] 条
     *（与 Room SESSION_MESSAGE_LIMIT 对齐）。写入路径已按 time.created 升序——
     * 超限时裁掉最旧一段；被裁消息的 parts / assistantMessageIds 同步清理。
     * 未超限时 O(1)（仅 size 检查）。更早历史由冷存桶 + loadAround 按需加载。
     */
    private fun applyMessageCap(sessionId: String) {
        var droppedIds: Set<String> = emptySet()
        _messages.update { current ->
            val msgs = current[sessionId] ?: return@update current
            if (msgs.size <= MEMORY_SESSION_MESSAGE_LIMIT) return@update current
            val overflow = msgs.size - MEMORY_SESSION_MESSAGE_LIMIT
            droppedIds = msgs.subList(0, overflow).map { it.id }.toHashSet()
            current + (sessionId to msgs.subList(overflow, msgs.size))
        }
        if (droppedIds.isNotEmpty()) {
            _parts.update { p -> p.filterKeys { it !in droppedIds } }
            assistantMessageIds.removeAll(droppedIds)
            if (BuildConfig.DEBUG) AppLogger.d(TAG, "Capped " + sessionId.take(12) + " to " + MEMORY_SESSION_MESSAGE_LIMIT + " msgs (dropped " + droppedIds.size + ")")
        }
    }

    /**
     * SSE_PRIORITY（原 setMessages 语义）：
     * - messages: [mergeMessageMeta] 合并——SSE 流式内容优先，REST 仅兜底完成时间
     * - parts: [mergePartsList]——更长文本胜出（保护 SSE 累积）
     * - 诊断日志保留（标签 [setMessages]）
     */
    private fun upsertSsePriority(sessionId: String, incoming: List<MessageWithParts>) {
        // 在 update lambda 外预排序 incoming（避免 CAS 重试时多次排序）
        val incomingSorted = incoming.map { it.info }.sortedBy { it.time.created }
        // #1657（P3）：tokens/cost 变更检测——SSE_PRIORITY 原本只更新内存热视图，
        // REST 刷新带回的 tokens/cost 不落 Room（V2 SSE 整 turn 不发 message.updated，
        // REST 是 tokens 唯一可靠来源）→ cached_messages 的 payload 停留在流式期
        // 骨架快照（tokens=null）→ 冷启动/离线 seed 后统计图标短暂缺失。
        // 在 update 内对比「合并前后」的 tokens/cost（消息不在 existing = null→值
        // 视为变更），变更行于 parts 合并后经 [persistSseUpdate] 增量落盘。
        // CAS 重试重复 add 同 id 幂等；值未变的重复刷新 0 写库——检测即节流
        //（SSE_PRIORITY 仅由 REST 快照触发，不在 delta 批处理路径上）。
        val tokensChangedIds = HashSet<String>()
        _messages.update { current ->
            val existing = current[sessionId] ?: emptyList()
            // O(n+m) 两路归并替代 O((n+m) log(n+m)) 全量排序（见 mergeSortedMessages 前提）
            // #485：REST 快照归并后按服务端 created 重排（mergeRestSnapshot）——
            // 保位契约在「user 行 created 被 REST 权威前跳」时破坏有序前提，
            // t_ 键漂到信封 → 子树换血 → asyncTerminal Loading≈0 闪灭。
            val merged = MessageMergeEngine.mergeRestSnapshot(existing, incomingSorted) { sse, inc ->
                MessageMergeEngine.mergeMessageMeta(sse, inc)
            }
            val assistantBeforeById = HashMap<String, Message.Assistant>(existing.size)
            for (m in existing) if (m is Message.Assistant) assistantBeforeById[m.id] = m
            for (m in merged) {
                if (m !is Message.Assistant) continue
                val before = assistantBeforeById[m.id]
                if (m.tokens != before?.tokens || m.cost != before?.cost) tokensChangedIds.add(m.id)
            }
            current + (sessionId to merged)
        }
        incoming.forEach { if (it.info is Message.Assistant) assistantMessageIds.add(it.info.id) }
        val partsMap = incoming.associate { it.info.id to it.parts }
        _parts.update { current ->
            val merged = partsMap.mapValues { (messageId, incomingParts) ->
                val existingParts = current[messageId]
                if (existingParts != null) {
                    MessageMergeEngine.mergePartsList(existingParts, incomingParts)
                } else {
                    incomingParts
                }
            }
            current + merged
        }
        // #1657：tokens/cost 变更行落盘（payload = 合并后内存快照，含最终 tokens →
        // 下次冷启动 seed 即带统计）。与 REST_AUTHORITY 落盘同款：复用 persistQueue
        // 单写 actor，fire-and-forget，写失败静默（内存视图不受影响）。
        if (tokensChangedIds.isNotEmpty()) {
            persistSseUpdate(sessionId, tokensChangedIds.toList())
        }
    }

    /**
     * REST_AUTHORITY（原 replaceMessages 语义）：
     * - messages: incoming 覆盖 existing 元数据（incomingById[msg.id]?.info ?: msg）；
     *   existing 独有的消息保留（处理 REST 快照与新 SSE 连接的时间窗口）
     * - parts: [mergePartsList]——与 SSE_PRIORITY 相同（更长文本胜出）
     */
    private fun upsertRestAuthority(sessionId: String, incoming: List<MessageWithParts>) {
        // 在 update lambda 外预排序 incoming（避免 CAS 重试时多次排序）
        val incomingSorted = incoming.map { it.info }.sortedBy { it.time.created }
        _messages.update { current ->
            val existing = current[sessionId] ?: emptyList()
            // O(n+m) 两路归并替代 O((n+m) log(n+m)) 全量排序（见 mergeSortedMessages 前提）
            // REST_AUTHORITY：同 id 时 incoming 覆盖（原 `incomingById[msg.id]?.info ?: msg`）。
            // 2026-08-15 修正（顶部 token 统计消失回归）：V2 REST 契约不返回
            // tokens/cost（V2Mappers 无映射），纯覆盖会把 SSE step.ended 写入的
            // tokens 抹掉 → lastContextTokens=0 → 顶部导航栏 context 指示器消失。
            // Assistant 改字段级合并（mergeAssistantMeta：incoming 非空字段权威、
            // 空字段保留 existing）——REST 权威语义不变，元数据不再丢失。
            val merged = MessageMergeEngine.mergeRestSnapshot(existing, incomingSorted) { e, inc ->
                if (e is Message.Assistant && inc is Message.Assistant) {
                    MessageMergeEngine.mergeAssistantMeta(e, inc)
                } else if (e is Message.User && inc is Message.User) {
                    // #395：REST 权威覆盖用户消息，但保留客户端发送路径标记 viaSteer
                    //（V2 REST 持久化载荷不含 delivery，纯覆盖会丢插话徽标）。
                    if (e.viaSteer) inc.copy(viaSteer = true) else inc
                } else {
                    inc
                }
            }
            current + (sessionId to merged)
        }
        incoming.forEach { if (it.info is Message.Assistant) assistantMessageIds.add(it.info.id) }
        val partsMap = incoming.associate { it.info.id to it.parts }
        _parts.update { current ->
            val merged = partsMap.mapValues { (messageId, incomingParts) ->
                val existingParts = current[messageId]
                if (existingParts != null) {
                    MessageMergeEngine.mergePartsList(existingParts, incomingParts)
                } else {
                    incomingParts
                }
            }
            current + merged
        }
        // 落盘（2026-08-11 修复）：REST refresh 合并后持久化——completed/内容更新
        // 写入 Room。否则 SSE 完成事件丢失时数据库永远 completed==null，重启后
        // seed 恢复旧状态 → UI 把已结束消息当流式（"Thinking…" 一直涨）。
        persistSseUpdate(sessionId, incoming.map { it.info.id })
    }

    /**
     * APPEND_ONLY（原 mergeMessages 语义）：
     * - 先 parts 后 messages（闪烁规避：避免 combine flow 看到新消息却无 part）
     * - parts: 仅添加 existing 中缺失的 messageId（不合并已有 parts）
     * - messages: existingById[newMsg.id] ?: newMsg（仅补充缺失，已有不变）
     */
    private fun upsertAppendOnly(sessionId: String, incoming: List<MessageWithParts>) {
        val incomingMsgs = incoming.map { it.info }.sortedBy { m -> m.time.created }
        // 先更新 parts，再更新 messages。这避免了 combine flow 看到
        // 新消息却没有对应 part 时的闪烁（P5-3 过滤器会临时移除它们）。
        _parts.update { currentParts ->
            val existingKeys = currentParts.keys
            val newParts = incoming
                .filter { it.info.id !in existingKeys }
                // #234 战役二封洞：appendOnly 直通路径同样滤零信息 part
                //（此前仅靠上游 REST mapper 过滤兜底——不变量对本路径结构性缺防）。
                .associate { it.info.id to MessageMergeEngine.sanitized(it.parts) }
            currentParts + newParts
        }
        incoming.forEach { if (it.info is Message.Assistant) assistantMessageIds.add(it.info.id) }
        _messages.update { current ->
            val existing = current[sessionId] ?: emptyList()
            // 修复（2026-08-10）：APPEND_ONLY 应"合并"而非"替换"。
            // 原实现 `incomingMsgs.map { ... }` 把 _messages 替换为分页加载的"更早消息"，
            // 导致现有消息（含最新/底部消息）全部丢失——用户上滑分页后下滑
            // 看不到最底部的消息（消息流中消失）。
            // 正确语义（注释约定）：existing 保留 + incoming 中缺失的 messageId 补充，
            // 合并后按 time.created 排序（combine 依赖写入路径有序，见 MessageDataDelegate）。
            // O(n+m) 两路归并替代 O((n+m) log(n+m)) 全量排序（见 mergeSortedMessages 前提）；
            // APPEND_ONLY：同 id 时保留 existing（原 `existingById[newMsg.id] ?: newMsg`）
            current + (sessionId to mergeSortedMessages(existing, incomingMsgs) { e, _ -> e })
        }
    }

    // ============ 批量操作 ============
    fun clearForSession(sessionId: String) {
        val messageIds = _messages.value[sessionId]?.map { it.id }?.toSet() ?: emptySet()
        // #442 B案：bus 清理先于热视图移除（part ids 仅此刻可得）
        dev.leonardo.ocbeacon.ui.screens.chat.components.StreamingDeltaBus
            .clearParts(_parts.value.filterKeys { it in messageIds }.values.flatten().map { it.id })
        _messages.update { it - sessionId }
        _parts.update { it - messageIds }
        assistantMessageIds.removeAll(messageIds)
        lastDomainEventTimeMs.remove(sessionId)
        publishStructural("clearForSession")
        // 可观测性（#89 验证）：记录清理量
        dev.leonardo.ocbeacon.logging.AppLogger.d(
            "MsgEvent",
            "clearForSession: session=$sessionId messages=${messageIds.size} partsRemoved=$messageIds.size"
        )
    }

    fun clearForServer(sessionIds: Set<String>) {
        val messageIds = _messages.value
            .filterKeys { it in sessionIds }.values.flatten()
            .map { it.id }.toSet()
        dev.leonardo.ocbeacon.ui.screens.chat.components.StreamingDeltaBus
            .clearParts(_parts.value.filterKeys { it in messageIds }.values.flatten().map { it.id })
        _messages.update { it - sessionIds }
        _parts.update { it - messageIds }
        assistantMessageIds.removeAll(messageIds)
        publishStructural("clearForServer")
    }

    fun clearAll() {
        _messages.value = emptyMap()
        _parts.value = emptyMap()
        assistantMessageIds.clear()
        lastDomainEventTimeMs.clear()
        dev.leonardo.ocbeacon.ui.screens.chat.components.StreamingDeltaBus.clearAll()
        publishStructural("clearAll")
    }

    /**
     * 将会话中所有未完成的 assistant 消息标记为已完成。
     * 在 REST 兜底检测到服务器已空闲但 UI 仍显示流式时调用。
     *
     * @param messageId 非空时仅标记该消息（command.executed 事件是消息级的，
     *   用 messageId 精确终结，避免误杀同一会话中仍在流式的其他消息）；
     *   为空时标记整个会话（服务器空闲确认路径）。
     */
    fun markSessionIdle(sessionId: String, messageId: String = "") {
        var changedIds: List<String>? = null
        // #338：completed 回填与 created 腿同钟域——优先取分发点采集的域内基准
        //（DSH=服务器信封时刻），无基准回退本地钟（V2 本地构造域，语义不变）。
        // 逐消息 max(基准, 自身 created) 兜底：骨架消息（本地钟）在设备钟快于
        // 服务器时不产生负跨度（0 跨度由显示层按未知处理）。
        val fillNow = domainNowMs(sessionId)
        _messages.update { current ->
            val sessionMessages = current[sessionId] ?: return@update current
            val updated = sessionMessages.map { msg ->
                if (msg is Message.Assistant && msg.time.completed == null &&
                    (messageId.isEmpty() || msg.id == messageId)
                ) {
                    msg.copy(time = msg.time.copy(completed = maxOf(fillNow, msg.time.created)))
                } else {
                    msg
                }
            }
            val changed = updated.filterIndexed { i, m ->
                m != sessionMessages[i]
            }.map { it.id }
            if (changed.isNotEmpty()) changedIds = changed
            current + (sessionId to updated)
        }

        // 落盘：completed 标记持久化（2026-08-11 修复——否则数据库永远 null，
        // 重启 seed 后 UI 把已结束消息当流式，"Thinking…" 计时器一直涨）。
        changedIds?.takeIf { it.isNotEmpty() }?.let { ids ->
            persistSseUpdate(sessionId, ids)
        }

        // 为所有未完成的 Reasoning part 标记 time.end
        _parts.update { current ->
            val sessionMessages = _messages.value[sessionId] ?: return@update current
            val messageIds = sessionMessages
                .filter { msg -> messageId.isEmpty() || msg.id == messageId }
                .map { it.id }
            var changed = false
            val updated = current.toMutableMap()
            for (msgId in messageIds) {
                val msgParts = updated[msgId] ?: continue
                val updatedParts = msgParts.map { part ->
                    // #338：part end 同消息 completed 口径——域内基准（与 part start 同域）
                    val partEnd = fillNow
                    when {
                        part is Part.Text && part.time?.end == null -> {
                            changed = true
                            // #338：end 与 start 同域钳制（DSH part start=chunk 信封
                            // 时刻，早于最后一条消息事件的域内基准时取 start——
                            // 零跨度由显示层按未知处理，不产生负跨度）。
                            val start = part.time?.start?.takeIf { it > 0 } ?: 0L
                            val end = maxOf(partEnd, start)
                            part.copy(time = Part.Text.Time(
                                start = start.takeIf { it > 0 } ?: end,
                                end = end
                            ))
                        }
                        part is Part.Reasoning && part.time?.end == null -> {
                            changed = true
                            // #263 round2：start 未知时不得伪造 start=end（恒 0ms 症状）。
                            // 0 = 未知哨兵，显示层走本地冻结实测时长，不显示伪造值。
                            val rStart = part.time?.start?.takeIf { it > 0 } ?: 0L
                            part.copy(time = Part.Reasoning.Time(
                                start = rStart,
                                end = maxOf(partEnd, rStart)
                            ))
                        }
                        else -> part
                    }
                }
                if (changed) updated[msgId] = updatedParts
            }
            if (changed) updated else current
        }
        //（2026-10-03 审计 Gap B 补口：中断/REST 空闲终态化后撤销 bus live 覆盖
        //——与 MessagePartTimePatch 终态路径同款，防陈旧 live 残留到会话级清理）
        changedIds?.forEach { clearTerminalLiveParts(it) }
    }
}

