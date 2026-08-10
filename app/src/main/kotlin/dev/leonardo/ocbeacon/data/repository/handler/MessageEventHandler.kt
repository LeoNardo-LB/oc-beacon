package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.*
import kotlinx.coroutines.*
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

    private companion object {
        const val TAG = "MsgEventHandler"
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

        _parts.update { current ->
            var updated = current
            for (entry in batch) {
                val messageParts = updated[entry.messageId]?.toMutableList() ?: mutableListOf()
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
                updated = updated + (entry.messageId to messageParts)
            }
            updated
        }

        // SSE 双写：48ms 批处理已聚合——按 sessionId 分组落盘受影响的消息。
        // 不逐 delta 写（会写放大）；批处理后一次性写。
        val bySession = batch.groupBy { it.sessionId }
        bySession.forEach { (sessionId, deltas) ->
            persistSseUpdate(sessionId, deltas.map { it.messageId }.distinct())
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
        _messages.update { current ->
            val msgs = current[sessionId]?.toMutableList() ?: mutableListOf()
            val idx = msgs.indexOfFirst { it.id == event.info.id }
            // DIAG 清理（2026-08-10）：移除 update 内的全量 filter + 日志——
            // 每次 MessageUpdated 都 O(n) 扫描 1896 条消息（仅用于日志），
            // SSE 活跃时每秒多次 → 真机掉帧（性能根因之一）。
            if (idx >= 0) {
                msgs[idx] = event.info
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
    }

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
        val msgs = _messages.value[sessionId]?.filter { it.id in messageIds } ?: return
        if (msgs.isEmpty()) return
        val parts = _parts.value
        val payload = msgs.map { MessageWithParts(it, parts[it.id] ?: emptyList()) }
        batchScope.launch {
            store.upsertMessages(sessionId, payload, persistOldBeyondWindow = false)
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
                // 新 part 到达——对所有消息类型保持文本不变。
                // 旧代码会剥离 assistant 消息的文本（假设 SSE delta 会重新累积它）。
                // 但若 delta 被错过（SSE 重连、网络中断），文本将永久丢失——
                // 用户会看到空气泡，直到手动刷新。
                // delta flush 的 endsWith() 去重 + mergePart 的"更长文本胜出"
                // 一起处理潜在重叠且不丢数据。
                messageParts.add(event.part)
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
                if (incoming.text.length >= existing.text.length) incoming
                else existing.copy(time = incoming.time, metadata = incoming.metadata)
            }
            existing is Part.Reasoning && incoming is Part.Reasoning -> {
                if (incoming.text.length >= existing.text.length) incoming
                else existing.copy(time = incoming.time, metadata = incoming.metadata)
            }
            else -> incoming
        }
    }

    private fun mergePartsList(existingParts: List<Part>, incomingParts: List<Part>): List<Part> {
        val existingById = existingParts.associateBy { it.id }
        return incomingParts.map { incoming ->
            val existing = existingById[incoming.id]
            if (existing != null) mergePart(existing, incoming) else incoming
        }
    }

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

        // 对于 Assistant 消息：
        // - 若 SSE 显示已完成（流式结束），完全信任 SSE
        // - 若 SSE 显示未完成但 REST 显示已完成，信任 REST 的完成时间
        //   但保留 SSE 的其他字段（finish、tokens、cost 可能更新）
        return if (sse.time.completed != null) {
            sse  // SSE 拥有最终状态，优先使用它
        } else if (rest.time.completed != null) {
            // REST 显示已完成但 SSE 尚未看到——合并完成时间
            sse.copy(time = sse.time.copy(completed = rest.time.completed))
        } else {
            // 两者都未完成——优先 SSE（更新的流式状态）
            sse
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
            // REST_AUTHORITY：同 id 时 incoming 完全覆盖（原 `incomingById[msg.id]?.info ?: msg`）
            val merged = mergeSortedMessages(existing, incomingSorted) { _, inc -> inc }
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
            current + (sessionId to updated)
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

