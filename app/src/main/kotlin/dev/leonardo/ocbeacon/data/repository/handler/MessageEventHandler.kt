package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 消息和 part 数据的共享状态存储。
 *
 * 持有 `_messages`、`_parts` 和 `assistantMessageIds` 状态，这些状态在
 * 消息/part 生命周期中紧密耦合（例如 [handleMessagePartUpdated] 会查询
 * 由 [handleMessageUpdated] 填充的 `assistantMessageIds`；
 * [handleMessageUpdated] 为用户消息播种 `_parts`）。由于这种耦合，
 * 按子事件的分发位于专用 handler
 *（[MessagePartHandler]、[MessageUpdatedHandler]、[MessageRemovedHandler]）中，
 * 它们注入此存储并委托给其 `internal` handler。
 *
 * SSE 双写：当 [messageStore] 非 null（生产环境 Hilt 注入）时，SSE 流式更新
 * 会异步落盘到 Room，以便离线/重启后恢复。测试环境传 null 禁用双写。
 */
@Singleton
class MessageEventHandler @Inject constructor(
    private val messageStore: MessageCacheRepository?,
) {
    /** 测试用无参构造：禁用 SSE 双写。生产环境由 Hilt 注入非空 MessageCacheRepository。 */
    constructor() : this(null)

    internal companion object {
        const val TAG = "MsgEventHandler"

        /**
         * #95（H-4 泄漏）：单会话消息热视图内存上限——与 Room 侧
         * MessageStore.SESSION_MESSAGE_LIMIT（1000）对齐。超出后保留最新 N 条，
         * 被裁剪消息的 parts / assistantMessageIds 同步清理（更早历史由
         * 归档桶 + loadAround 按需分页加载，不依赖热视图）。
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
        if (BuildConfig.DEBUG) {
            // debug 级流式 flush 日志（节流：每 100 批打一次）——用于确认
            // delta 正在落库（"无回复/输出中断"排查的关键节点）。
            deltaFlushCounter++
            if (deltaFlushCounter % 100 == 1) {
                AppLogger.d(TAG, "[flush] deltas=${batch.size} (batch #${deltaFlushCounter}, first=${batch.first().messageId.take(12)})")
            }
        }

        _parts.update { current ->
            // #97（M-15）：原实现批内每 delta 都整份 Map 拷贝（updated + (...)）——
            // O(N×M)。改为一次 toMutableMap，批内按 messageId 聚合就地更新。
            val updated = current.toMutableMap()
            for (entry in batch) {
                val messageParts = (updated[entry.messageId] ?: emptyList()).toMutableList()
                val idx = messageParts.indexOfFirst { it.id == entry.partId }
                if (idx >= 0) {
                    val part = messageParts[idx]
                    val newPart = when (part) {
                        is Part.Text -> {
                            if (part.text.endsWith(entry.delta)) part  // 去重
                            else part.copy(text = part.text + entry.delta)
                        }
                        is Part.Reasoning -> part.copy(text = part.text + entry.delta)
                        else -> part
                    }
                    messageParts[idx] = newPart
                } else {
                    messageParts.add(Part.Text(
                        id = entry.partId,
                        sessionId = entry.sessionId,
                        messageId = entry.messageId,
                        text = entry.delta
                    ))
                }
                updated[entry.messageId] = messageParts
            }
            updated
        }

        // SSE 双写：#97（H-6）增量落盘——本批 delta 只追加到对应 part 行
        //（O(delta) 写，替代原整条消息 JSON 编码 + 全行重写）。
        // 按 (sessionId, messageId) 聚合 partId→文本（同 part 多次 delta 合并）。
        val store = messageStore ?: return
        val byMessage = batch.groupBy { it.sessionId to it.messageId }
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
                    mergeAssistantMeta(existing, event.info)
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
     * 由 [EventDispatcher.clearRevert] 调用，防止已回退的消息
     * 在回退过滤器清除时短暂重现。
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

    internal fun handleMessagePartUpdated(event: SseEvent.MessagePartUpdated) {
        val messageId = event.part.messageId
        val partId = event.part.id
        _parts.update { current ->
            val messageParts = current[messageId]?.toMutableList() ?: mutableListOf()
            val idx = messageParts.indexOfFirst { it.id == partId }
            if (idx >= 0) {
                val old = messageParts[idx]
                val merged = mergePart(old, event.part)
                messageParts[idx] = merged
            } else {
                // 防御（#87b）：part ID 契约差异——REST 快照的 text part id="" vs
                // SSE 的 id="prt_xxx"。按 id 找不到时会新增第二条 part → 同消息
                // 两条文本 part → UI 文本重复渲染（压测实测 "Got it. ... Got it. ..."）。
                // 对空 id 的 Text part 按内容匹配（相等/前缀）合并而非新增。
                val contentMatchIdx = if (partId.isBlank() && event.part is Part.Text) {
                    messageParts.indexOfFirst { existing ->
                        existing is Part.Text &&
                            (existing.text == event.part.text ||
                                existing.text.startsWith(event.part.text) ||
                                event.part.text.startsWith(existing.text))
                    }
                } else {
                    -1
                }
                if (contentMatchIdx >= 0) {
                    val old = messageParts[contentMatchIdx]
                    messageParts[contentMatchIdx] = mergePart(old, event.part)
                } else {
                    // 新 part 到达——对所有消息类型保持文本不变。
                    // 旧代码会剥离 assistant 消息的文本（假设 SSE delta 会重新累积它）。
                    // 但若 delta 被错过（SSE 重连、网络中断），文本将永久丢失——
                    // 用户会看到空气泡，直到手动刷新。
                    // delta flush 的 endsWith() 去重 + mergePart 的"更长文本胜出"
                    // 一起处理潜在重叠且不丢数据。
                    messageParts.add(event.part)
                }
            }
            current + (messageId to messageParts)
        }
    }

    /**
     * 合并 Part 更新：对于 Text/Reasoning，SSE delta 驱动的文本优先。
     *
     * 流式传输期间，SSE delta 增量累积文本。REST 同步可能返回比 delta 累积
     * 更新的快照（例如 REST 返回"你好世界"而 SSE 只累积了"你好"）。
     * 若我们取 REST 快照的更长文本，后续的 SSE delta（服务器在 REST 调用前
     * 已发送）会追加快照中已有的内容，导致重复。
     *
     * 修复：若现有（SSE）已有任何文本，保留它——SSE 是流式传输的真相源。
     * 仅当现有为空（part 刚创建）时才取传入的文本。
     * 始终取传入的元数据（time 等），因为 REST 可能有更新的元数据。
     */
    private fun mergePart(existing: Part, incoming: Part): Part {
        return when {
            existing is Part.Text && incoming is Part.Text -> {
                // 更长文本胜出：SSE 流式传输随时间累积更长文本，
                // REST 快照可能已过时。若传入文本更长（全新的完整替换），
                // 使用它；否则保留现有（保护流式文本）。
                // #109：时间取回退链——ended 事件 start=0（未知）时用 started
                // 记录的本地时刻，REST 真实时间戳（>0）优先。
                val time = Part.Text.Time(
                    start = incoming.time?.start?.takeIf { it > 0 }
                        ?: existing.time?.start?.takeIf { it > 0 }
                        ?: (incoming.time?.end ?: existing.time?.end) ?: 0L,
                    end = incoming.time?.end ?: existing.time?.end
                )
                if (incoming.text.length >= existing.text.length) incoming.copy(time = time)
                else existing.copy(time = time, metadata = incoming.metadata)
            }
            existing is Part.Reasoning && incoming is Part.Reasoning -> {
                val time = Part.Reasoning.Time(
                    start = incoming.time?.start?.takeIf { it > 0 }
                        ?: existing.time?.start?.takeIf { it > 0 }
                        ?: (incoming.time?.end ?: existing.time?.end) ?: 0L,
                    end = incoming.time?.end ?: existing.time?.end
                )
                if (incoming.text.length >= existing.text.length) incoming.copy(time = time)
                else existing.copy(time = time, metadata = incoming.metadata)
            }
            existing is Part.Tool && incoming is Part.Tool -> {
                var merged = incoming
                // 工具名保留：v2 中间事件（tool.input.ended/called 等）不带 name 字段，
                // 仅 tool.input.started 携带——若 incoming 缺名则保留 existing 的
                val incomingTool = merged.tool
                val existingTool = existing.tool
                if (incomingTool.isBlank() && existingTool.isNotBlank()) {
                    merged = merged.copy(tool = existingTool)
                }
                // input 保留：incoming 中间状态可能缺 input（input.ended 只有 output）
                val incomingInput = merged.stateInput()
                val existingInput = existing.stateInput()
                if (incomingInput.isEmpty() && existingInput.isNotEmpty()) {
                    merged = merged.withStateInput(existingInput)
                }
                // Tool part：SSE 中间状态（Running）可能缺少 metadata（如 subagent 子会话 ID），
                // 但 REST 快照/早期 SSE 已完成状态包含完整 metadata。
                // 若 incoming 缺少 metadata 而 existing 有，保留 existing 的 metadata，
                // 避免 subagent 卡片失去子会话跳转能力（backlog: subagent 卡片不可点击）。
                val incomingMetadata = merged.stateMetadata()
                val existingMetadata = existing.stateMetadata()
                if (incomingMetadata.isNullOrEmpty() && !existingMetadata.isNullOrEmpty()) {
                    merged = merged.withStateMetadata(existingMetadata)
                }
                merged
            }
            else -> incoming
        }
    }

    /** 提取 Part.Tool 的 state.metadata（各 ToolState 子类）。 */
    private fun Part.Tool.stateMetadata(): Map<String, kotlinx.serialization.json.JsonElement>? = when (val s = state) {
        is dev.leonardo.ocbeacon.domain.model.ToolState.Pending -> null // Pending 无 metadata 字段
        is dev.leonardo.ocbeacon.domain.model.ToolState.Running -> s.metadata
        is dev.leonardo.ocbeacon.domain.model.ToolState.Completed -> s.metadata
        is dev.leonardo.ocbeacon.domain.model.ToolState.Error -> s.metadata
    }

    /** 提取 Part.Tool 的 state.input（各 ToolState 子类）。 */
    private fun Part.Tool.stateInput(): Map<String, kotlinx.serialization.json.JsonElement> = when (val s = state) {
        is dev.leonardo.ocbeacon.domain.model.ToolState.Pending -> s.input
        is dev.leonardo.ocbeacon.domain.model.ToolState.Running -> s.input
        is dev.leonardo.ocbeacon.domain.model.ToolState.Completed -> s.input
        is dev.leonardo.ocbeacon.domain.model.ToolState.Error -> s.input
    }

    /** 用保留的 input 重建 Part.Tool（state 替换为携带 input 的副本）。 */
    private fun Part.Tool.withStateInput(
        input: Map<String, kotlinx.serialization.json.JsonElement>
    ): Part.Tool = copy(
        state = when (val s = state) {
            is dev.leonardo.ocbeacon.domain.model.ToolState.Pending -> s.copy(input = input)
            is dev.leonardo.ocbeacon.domain.model.ToolState.Running -> s.copy(input = input)
            is dev.leonardo.ocbeacon.domain.model.ToolState.Completed -> s.copy(input = input)
            is dev.leonardo.ocbeacon.domain.model.ToolState.Error -> s.copy(input = input)
        }
    )

    /** 用保留的 metadata 重建 Part.Tool（state 替换为携带 metadata 的副本）。 */
    private fun Part.Tool.withStateMetadata(
        metadata: Map<String, kotlinx.serialization.json.JsonElement>
    ): Part.Tool = copy(
        state = when (val s = state) {
            is dev.leonardo.ocbeacon.domain.model.ToolState.Pending -> s // Pending 无 metadata 字段，保留原样
            is dev.leonardo.ocbeacon.domain.model.ToolState.Running -> s.copy(metadata = metadata)
            is dev.leonardo.ocbeacon.domain.model.ToolState.Completed -> s.copy(metadata = metadata)
            is dev.leonardo.ocbeacon.domain.model.ToolState.Error -> s.copy(metadata = metadata)
        }
    )

    private fun mergePartsList(existingParts: List<Part>, incomingParts: List<Part>): List<Part> {
        // 2026-08-12 根因修复（流式内容消失）：
        // 1. incoming 为空（REST 流式消息 content 未提交 / SSE 部分更新）时
        //    保留 existing——原实现返回 [] 清空 SSE 累积文本。
        // 2. 保留 incoming 中不存在的已有 parts：REST text part id="" 与 SSE
        //    派生 id="msg_ord_N" 契约不一致（V2Mappers.kt:294 vs V2SseMapper
        //    derivePartId）→ 原实现丢弃 existing 独有（SSE 累积）文本。
        //    顺序：incoming（REST 权威）在前，SSE 独有追加在后；完成后 REST
        //    全量返回 → preserved 为空 → 顺序完全按 REST。
        if (incomingParts.isEmpty()) return existingParts
        val existingById = existingParts.associateBy { it.id }
        val merged = incomingParts.map { incoming ->
            val existing = existingById[incoming.id]
            if (existing != null) mergePart(existing, incoming) else incoming
        }
        val incomingIds = incomingParts.mapTo(HashSet()) { it.id }
        val preserved = existingParts.filter { it.id !in incomingIds }
        return dedupOverlappingTextParts(merged + preserved)
    }

    /**
     * #109（D2-01 兜底）：part id 契约演进期间（Room 旧数据 id=""、旧版派生
     * id `msg_ord_N` 与新版 `msg_type_ord_N`），同一逻辑 part 的两个版本可能
     * 同时存活 → 已完结消息文本双份渲染。对 Text/Reasoning 按**内容重叠**
     * （相等/前缀）去重：至少一侧 id 非新版契约时才合并（两条新版 id 不同的
     * part 视为真不同），保留文本更长、等长优先非空 id 的一条。
     * 与 handleMessagePartUpdated 的 #87b 空内容匹配防御同一权衡。
     */
    private fun dedupOverlappingTextParts(parts: List<Part>): List<Part> {
        if (parts.size < 2) return parts
        val result = mutableListOf<Part>()
        outer@ for (p in parts) {
            for (i in result.indices) {
                val r = result[i]
                val sameKind = when {
                    r is Part.Text && p is Part.Text -> true
                    r is Part.Reasoning && p is Part.Reasoning -> true
                    else -> false
                }
                if (!sameKind) continue
                val rt = (r as? Part.Text)?.text ?: (r as? Part.Reasoning)?.text ?: continue
                val pt = (p as? Part.Text)?.text ?: (p as? Part.Reasoning)?.text ?: continue
                val overlaps = rt == pt ||
                    (rt.length <= pt.length && pt.startsWith(rt)) ||
                    (pt.length <= rt.length && rt.startsWith(pt))
                if (overlaps && (isNewPartId(r.id).not() || isNewPartId(p.id).not())) {
                    result[i] = if (pt.length > rt.length || (pt.length == rt.length && p.id.isNotBlank())) p else r
                    continue@outer
                }
            }
            result.add(p)
        }
        return result
    }

    /** #109 新版派生 id 契约：`<msg>_text_ord_N` / `<msg>_reasoning_ord_N`。 */
    private fun isNewPartId(id: String): Boolean =
        id.contains("_text_ord_") || id.contains("_reasoning_ord_")

    /**
     * 合并两个按 [Message.time.created] 升序的消息列表，按 id 去重。
     *
     * 等价于 `(existing + incomingSorted).distinctBy { it.id }.map { merge(it) }.sortedBy { it.time.created }`，
     * 但复杂度 O(existing.size + incomingSorted.size)（线性两路归并），
     * 替代 O((n+m) log(n+m)) 全量排序——1000-2000 条会话每次 upsert 节省约 10000-40000 次比较。
     *
     * **前提**（由本类写入路径维持，实际语义成立）：
     * - [existing] 已按 created 升序（所有写入路径输出有序）
     * - [incomingSorted] 由调用方保证按 created 升序（Kotlin `sortedBy` 稳定）
     * - 同 id 消息在两列表中 created 一致：消息创建时间为固有属性（服务器不变更），
     *   [mergeMessageMeta] 仅修改 completed（不改 created）；
     *   [MergeStrategy.REST_AUTHORITY] 虽用 incoming 完全覆盖，但 REST 同一消息的 created 与 SSE 一致
     *
     * **去重 / 稳定语义**（与原 distinctBy + 稳定 sortBy 完全一致）：
     * - 同 id：取 [merge] 结果，位置取 existing 的位置
     * - existing 独有 id：保留 existing
     * - incoming 独有 id：插入到 created 升序对应位置
     * - 相同 created 不同 id：existing 在前（稳定排序——与 `(ex+inc).distinctBy.sortedBy` 中
     *   distinctBy 保留 existing 在前 + sortedBy 稳定保持原序一致）
     *
     * **Bug 1 修复（同 created 顺序反转，2026-08-10）**：当 existing 项的 id 被 incoming 覆盖且
     * 与 incoming 中某独有项同 created 时，旧实现跳过 existing 项后让 incoming 独有项先入 result，
     * 导致合并版本排到独有项之后。修复：existing 项被覆盖时立即 merge 并入 result（保持原位），
     * 用 [added] 标记防止 incoming 中该 id 再次加入。
     *
     * **Bug 2 修复（同 id 不去重，2026-08-10）**：existing/incoming 内含同 id 重复项时，
     * 旧实现全部追加。修复：[added] 集合统一跟踪已加入的 id（等价于 distinctBy 的 seen 集合），
     * 后续遇到同 id 跳过（保留首个，与 distinctBy 语义一致）。
     */
    internal fun mergeSortedMessages(
        existing: List<Message>,
        incomingSorted: List<Message>,
        merge: (existingMsg: Message, incomingMsg: Message) -> Message,
    ): List<Message> {
        // O(m)：incoming id → 首个版本（与 distinctBy 保留首个语义一致，不用 associateBy 因其保留末个）
        val incomingById = LinkedHashMap<String, Message>(incomingSorted.size)
        for (msg in incomingSorted) {
            if (msg.id !in incomingById) incomingById[msg.id] = msg
        }
        // 已加入 result 的 id 集合：等价于 distinctBy 的 seen 集合，
        // 统一处理所有来源（existing/incoming 内部重复、跨列表同 id）的去重。
        val added = HashSet<String>()
        val result = ArrayList<Message>(existing.size + incomingSorted.size)
        var i = 0  // existing 游标
        var j = 0  // incomingSorted 游标
        while (i < existing.size && j < incomingSorted.size) {
            val e = existing[i]
            val inc = incomingSorted[j]
            when {
                e.id == inc.id -> {
                    // 同 id：合并，前进两个游标（等价于 distinctBy 保留 existing 位置 + map 替换内容）
                    // 检查 added：existing/incoming 内部可能同 id 重复，首次已处理，后续只前进游标
                    if (e.id !in added) {
                        result.add(merge(e, inc))
                        added.add(e.id)
                    }
                    i++; j++
                }
                e.time.created <= inc.time.created -> {
                    // existing 较早或并列（稳定排序：existing 优先）
                    if (e.id !in added) {
                        if (e.id in incomingById) {
                            // Bug 1 修复：e 被 incoming 覆盖——立即 merge 保持原位，
                            // 否则 incoming 中与 e 同 created 的独有项会错误地排到 e 的合并版本前
                            result.add(merge(e, incomingById[e.id]!!))
                        } else {
                            result.add(e)
                        }
                        added.add(e.id)
                    }
                    i++
                }
                else -> {
                    // incoming 较早：跳过已加入的同 id 条目（Bug 2 修复——incoming 内同 id 重复）
                    if (inc.id !in added) {
                        result.add(inc)
                        added.add(inc.id)
                    }
                    j++
                }
            }
        }
        // existing 剩余
        while (i < existing.size) {
            val e = existing[i]
            if (e.id !in added) {
                val incVersion = incomingById[e.id]
                if (incVersion != null) {
                    // e 被 incoming 覆盖且尚未加入：merge 保持原位
                    result.add(merge(e, incVersion))
                } else {
                    result.add(e)
                }
                added.add(e.id)
            }
            i++
        }
        // incoming 剩余：跳过已加入的（Bug 2 修复）
        while (j < incomingSorted.size) {
            val inc = incomingSorted[j]
            if (inc.id !in added) {
                result.add(inc)
                added.add(inc.id)
            }
            j++
        }
        return result
    }

    /**
     * 合并消息的 SSE 和 REST 版本。
     * SSE 对内容更新（流式传输），但 REST 可能有 SSE 尚未投递的完成信息。
     *
     * 注意：REST completed 仅在 SSE 尚未完成时作为兜底合并（SSE 完成事件
     * 丢失时防止消息永不完成）；SSE 已完成则完全信任 SSE。
     */
    private fun mergeMessageMeta(sse: Message, rest: Message): Message {
        // 对于用户消息：REST 是权威的（无流式传输）
        if (sse is Message.User) return rest
        if (sse !is Message.Assistant) return rest

        // 2026-08-15：REST 元数据兜底——SSE 侧 modelId/providerId/agent 为空时
        // 采纳 REST 值。V2 SSE step.ended 契约本就不含模型信息（曾整替换抹掉
        // step.started 写入的值），REST listMessages 的 model 映射完整——
        // 让 REST 兜底路径真正能修复统计栏的模型名。
        val restA = rest as? Message.Assistant
        fun withMeta(m: Message.Assistant): Message.Assistant = if (restA == null) m else m.copy(
            modelId = m.modelId ?: restA.modelId,
            providerId = m.providerId ?: restA.providerId,
            agent = m.agent ?: restA.agent
        )

        // 对于 Assistant 消息：
        // - 若 SSE 显示已完成（流式结束），完全信任 SSE
        // - 若 SSE 显示未完成但 REST 显示已完成，信任 REST 的完成时间
        //   但保留 SSE 的其他字段（finish、tokens、cost 可能更新）
        return if (sse.time.completed != null) {
            withMeta(sse)  // SSE 拥有最终状态，优先使用它（模型元数据 REST 兜底）
        } else if (rest.time.completed != null) {
            // REST 显示已完成但 SSE 尚未看到——合并完成时间
            withMeta(sse.copy(time = sse.time.copy(completed = rest.time.completed)))
        } else {
            // 两者都未完成——优先 SSE（更新的流式状态）
            withMeta(sse)
        }
    }

    internal fun handleMessagePartDelta(event: SseEvent.MessagePartDelta) {
        // 缓冲 delta 以批量 flush（48ms 窗口）——将重组频率
        // 从逐 token 降至约 20 次/秒，消除布局抖动。
        val partType = when (_parts.value[event.messageId]
            ?.firstOrNull { it.id == event.partId }) {
            is Part.Reasoning -> "reasoning"
            else -> "text"
        }
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
     * 未超限时 O(1)（仅 size 检查）。更早历史由归档桶 + loadAround 按需加载。
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
        _messages.update { current ->
            val existing = current[sessionId] ?: emptyList()
            // O(n+m) 两路归并替代 O((n+m) log(n+m)) 全量排序（见 mergeSortedMessages 前提）
            val merged = mergeSortedMessages(existing, incomingSorted) { sse, inc ->
                mergeMessageMeta(sse, inc)
            }
            current + (sessionId to merged)
        }
        incoming.forEach { if (it.info is Message.Assistant) assistantMessageIds.add(it.info.id) }
        val partsMap = incoming.associate { it.info.id to it.parts }
        _parts.update { current ->
            val merged = partsMap.mapValues { (messageId, incomingParts) ->
                val existingParts = current[messageId]
                if (existingParts != null) {
                    mergePartsList(existingParts, incomingParts)
                } else {
                    incomingParts
                }
            }
            current + merged
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
                    mergeAssistantMeta(e, inc)
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
                    mergePartsList(existingParts, incomingParts)
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
                .associate { it.info.id to it.parts }
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

    /** SSE_PRIORITY 策略的薄委托。语义见 [upsertMessages]。 */
    @Deprecated("Use upsertMessages(sessionId, newMessages, MergeStrategy.SSE_PRIORITY)", ReplaceWith("upsertMessages(sessionId, newMessages, MergeStrategy.SSE_PRIORITY)"))
    fun setMessages(sessionId: String, newMessages: List<MessageWithParts>) =
        upsertMessages(sessionId, newMessages, MergeStrategy.SSE_PRIORITY)

    /** APPEND_ONLY 策略的薄委托。语义见 [upsertMessages]。 */
    @Deprecated("Use upsertMessages(sessionId, newMessages, MergeStrategy.APPEND_ONLY)", ReplaceWith("upsertMessages(sessionId, newMessages, MergeStrategy.APPEND_ONLY)"))
    fun mergeMessages(sessionId: String, newMessages: List<MessageWithParts>) =
        upsertMessages(sessionId, newMessages, MergeStrategy.APPEND_ONLY)

    /**
     * REST_AUTHORITY 策略的薄委托。语义见 [upsertMessages]。
     *
     * 用 REST 数据替换会话的所有消息和 part。
     * 将 REST 视为真相源，覆盖任何现有本地数据。用于 SSE 重连恢复。
     *
     * 仅 SSE 才有的消息（不在 REST 响应中）会被保留，以处理
     * REST 快照与新 SSE 连接建立之间的时间窗口。
     */
    @Deprecated("Use upsertMessages(sessionId, newMessages, MergeStrategy.REST_AUTHORITY)", ReplaceWith("upsertMessages(sessionId, newMessages, MergeStrategy.REST_AUTHORITY)"))
    fun replaceMessages(sessionId: String, newMessages: List<MessageWithParts>) =
        upsertMessages(sessionId, newMessages, MergeStrategy.REST_AUTHORITY)

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
     * 在 REST 回退检测到服务器已空闲但 UI 仍显示流式时调用。
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
                            part.copy(time = Part.Reasoning.Time(
                                start = part.time?.start ?: partEnd,
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

