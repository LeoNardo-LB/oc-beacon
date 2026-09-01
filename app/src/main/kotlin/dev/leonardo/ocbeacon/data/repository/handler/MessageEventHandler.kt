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
        return when (event) {
            is SseEvent.MessageUpdated -> { handleMessageUpdated(event); true }
            is SseEvent.MessageRemoved -> { handleMessageRemoved(event); true }
            is SseEvent.MessagePartUpdated -> { handleMessagePartUpdated(event); true }
            is SseEvent.MessagePartDelta -> { handleMessagePartDelta(event); true }
            is SseEvent.MessagePartRemoved -> { handleMessagePartRemoved(event); true }
            else -> false
        }
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
    }

    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()

    private val _parts = MutableStateFlow<Map<String, List<Part>>>(emptyMap())
    val parts: StateFlow<Map<String, List<Part>>> = _parts.asStateFlow()

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

    // ── SSE delta 批处理（48ms 窗口）──────────────────────────────
    // 缓冲传入的 delta 并每 48ms 刷新一次，以降低
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

    // ---- 持久化 actor（#57）----
    // 所有 SSE 双写落盘请求经 Channel 入队，由单一写协程串行处理：
    // - 协程数恒为 1（原实现每 48ms flush 一个 fire-and-forget 协程，
    //   活跃流式下无上限创建）
    // - Channel BUFFERED 提供背压（写入慢时请求排队，不丢）
    // - App 进程消亡时随进程终止（MessageEventHandler 为 @Singleton）
    private data class PersistRequest(
        val store: MessageCacheRepository,
        val sessionId: String,
        val payload: List<MessageWithParts>,
        /** #97（H-6）：非空时走增量落盘（appendPartTexts），空时全量 upsert。 */
        val incrementalDeltas: List<dev.leonardo.ocbeacon.data.local.PartDelta> = emptyList(),
    )

    private val persistQueue = Channel<PersistRequest>(Channel.BUFFERED)

    /** N-1：persistQueue 满时 trySend 静默丢写的可观测性计数（内存视图不受影响，落盘由后续写补齐）。 */
    private var droppedPersistWrites = 0

    private fun onPersistQueueFull() {
        droppedPersistWrites++
        if (droppedPersistWrites == 1 || droppedPersistWrites % 50 == 0) {
            AppLogger.w(TAG, "persist queue full, dropped $droppedPersistWrites write requests (Room slower than SSE production)")
        }
    }

    init {
        batchScope.launch {
            for (req in persistQueue) {
                try {
                    if (req.incrementalDeltas.isNotEmpty()) {
                        // #97（H-6）：增量写——只追加 delta 文本 + 骨架消息
                        req.store.appendPartTexts(req.sessionId, req.payload, req.incrementalDeltas)
                    } else {
                        req.store.upsertMessages(req.sessionId, req.payload, persistOldBeyondWindow = false)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 写失败静默（MessageStore 内部已捕获，内存视图不受影响）
                }
            }
        }
    }

    private fun scheduleFlush() {
        // 不要取消进行中的定时器——那会在 token 到达速率 > 1/48ms 时
        // 饿死 flush。让 delta 累积；运行中的定时器触发时会一次性 flush 它们。
        if (batchJob?.isActive == true) return
        batchJob = batchScope.launch {
            delay(48)
            flushPendingDeltas()
        }
    }

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
            if (persistQueue.trySend(
                    PersistRequest(
                        store = store,
                        sessionId = sessionId,
                        payload = payload,
                        incrementalDeltas = incrementalDeltas,
                    )
                ).isFailure
            ) {
                onPersistQueueFull()
            }
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
                    MessageMergeEngine.mergeAssistantMeta(existing, event.info)
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
     * - 沿用 48ms 批处理节奏：调用方在 flushPendingDeltas（已聚合）或
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
        // #57：入队由单写协程处理（不再每 48ms 创建 fire-and-forget 协程）
        if (persistQueue.trySend(PersistRequest(store, sessionId, payload)).isFailure) {
            onPersistQueueFull()
        }
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
        _parts.update { it.filterKeys { msgId -> msgId !in removedIds } }
        assistantMessageIds.removeAll(removedIds)

        if (BuildConfig.DEBUG) AppLogger.d(TAG, "Pruned ${removedIds.size} reverted messages for session ${sessionId.take(12)}")
    }

    internal fun handleMessageRemoved(event: SseEvent.MessageRemoved) {
        _messages.update { current ->
            val sessionMessages = current[event.sessionId]?.filter { it.id != event.messageId }
            if (sessionMessages != null) current + (event.sessionId to sessionMessages) else current
        }
        _parts.update { it - event.messageId }
        assistantMessageIds.remove(event.messageId)
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
        // 缓冲 delta 以批量 flush（48ms 窗口）——将重组频率
        // 从逐 token 降至约 20 次/秒，消除布局抖动。
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
        applyMessageCap(sessionId)
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
        //（SSE_PRIORITY 仅由 REST 快照触发，不在 48ms delta 批处理路径上）。
        val tokensChangedIds = HashSet<String>()
        _messages.update { current ->
            val existing = current[sessionId] ?: emptyList()
            // O(n+m) 两路归并替代 O((n+m) log(n+m)) 全量排序（见 mergeSortedMessages 前提）
            val merged = mergeSortedMessages(existing, incomingSorted) { sse, inc ->
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
            val merged = mergeSortedMessages(existing, incomingSorted) { e, inc ->
                if (e is Message.Assistant && inc is Message.Assistant) {
                    MessageMergeEngine.mergeAssistantMeta(e, inc)
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
        _messages.update { it - sessionId }
        _parts.update { it - messageIds }
        assistantMessageIds.removeAll(messageIds)
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
        _messages.update { it - sessionIds }
        _parts.update { it - messageIds }
        assistantMessageIds.removeAll(messageIds)
    }

    fun clearAll() {
        _messages.value = emptyMap()
        _parts.value = emptyMap()
        assistantMessageIds.clear()
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
        _messages.update { current ->
            val sessionMessages = current[sessionId] ?: return@update current
            val now = System.currentTimeMillis()
            val updated = sessionMessages.map { msg ->
                if (msg is Message.Assistant && msg.time.completed == null &&
                    (messageId.isEmpty() || msg.id == messageId)
                ) {
                    msg.copy(time = msg.time.copy(completed = now))
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
                    val partEnd = System.currentTimeMillis()
                    when {
                        part is Part.Text && part.time?.end == null -> {
                            changed = true
                            part.copy(time = Part.Text.Time(
                                start = part.time?.start ?: partEnd,
                                end = partEnd
                            ))
                        }
                        part is Part.Reasoning && part.time?.end == null -> {
                            changed = true
                            // #263 round2：start 未知时不得伪造 start=end=partEnd（恒 0ms 症状）。
                            // 0 = 未知哨兵，显示层走本地冻结实测时长，不显示伪造值。
                            part.copy(time = Part.Reasoning.Time(
                                start = part.time?.start?.takeIf { it > 0 } ?: 0L,
                                end = partEnd
                            ))
                        }
                        else -> part
                    }
                }
                if (changed) updated[msgId] = updatedParts
            }
            if (changed) updated else current
        }
    }
}

