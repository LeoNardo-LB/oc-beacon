package dev.leonardo.ocbeacon.data.repository.handler

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
 */
@Singleton
class MessageEventHandler @Inject constructor() {

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
        val role = when (event.info) { is Message.User -> "user"; is Message.Assistant -> "assistant" }
        _messages.update { current ->
            val msgs = current[sessionId]?.toMutableList() ?: mutableListOf()
            val idx = msgs.indexOfFirst { it.id == event.info.id }
            val isUpdate = idx >= 0
            // DIAG：处理前记录状态
            val userMsgs = msgs.filter { it is Message.User }
            AppLogger.i("MsgDiag", "[MsgUpdated] ENTER role=$role eventId=${event.info.id.take(16)} " +
                "session=${sessionId.take(8)} total=${msgs.size} " +
                "userCount=${userMsgs.size} isUpdate=$isUpdate")
            if (idx >= 0) {
                msgs[idx] = event.info
            } else {
                msgs.add(event.info)
                msgs.sortBy { it.time.created }
            }
            // DIAG：处理后记录状态
            val afterUser = msgs.filter { it is Message.User }
            // 乐观消息已从缓存中移除，单条 MessageUpdated
            // 对用户消息而言，用户计数合法增加 1。仅当增加超过 1 时
            // 才告警（表示存在逻辑回归）。
            if (afterUser.size > userMsgs.size + 1) {
                AppLogger.w("MsgDiag", "[MsgUpdated] ⚠️ unexpected user count increase: ${userMsgs.size}→${afterUser.size} " +
                    "userIds=${afterUser.joinToString(",") { it.id.take(16) }}")
            }
            current + (sessionId to msgs)
        }
        if (event.info is Message.Assistant) {
            assistantMessageIds.add(event.info.id)
            AppLogger.d("UnreadDiag", "[MsgUpdated] sid=${sessionId.take(12)} msg=${event.info.id.take(12)} completed=${event.info.time.completed}")
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
        @Suppress("DEPRECATION")
        val thread = Thread.currentThread().id
        _parts.update { current ->
            val messageParts = current[messageId]?.toMutableList() ?: mutableListOf()
            val idx = messageParts.indexOfFirst { it.id == partId }
            if (idx >= 0) {
                val old = messageParts[idx]
                val merged = mergePart(old, event.part)
                // 诊断：记录 Text/Reasoning part 的文本变更
                if (old is Part.Text && event.part is Part.Text) {
                    val oldLen = old.text.length
                    val newLen = (merged as Part.Text).text.length
                    val incLen = event.part.text.length
                    if (newLen != incLen) {
                        AppLogger.w(TAG, "[PartUpdated] t=$thread msg=$messageId part=$partId " +
                            "old=$oldLen inc=$incLen merged=$newLen " +
                            "(kept SSE text, discarded REST snapshot)")
                    }
                }
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

    // ============ 批量操作 ============

    fun setMessages(sessionId: String, newMessages: List<MessageWithParts>) {
        @Suppress("DEPRECATION")
        val thread = Thread.currentThread().id
        _messages.update { current ->
            val existing = current[sessionId] ?: emptyList()
            val incomingById = newMessages.associateBy { it.info.id }
            val hasRestUserMsgs = newMessages.any { it.info is Message.User }
            val merged = (existing + newMessages.map { it.info })
                .distinctBy { it.id }
                .map { msg ->
                    val incoming = incomingById[msg.id]
                    if (incoming != null) {
                        mergeMessageMeta(msg, incoming.info)
                    } else {
                        msg
                    }
                }
                .sortedBy { it.time.created }
            // DIAG：记录合并结果
            val beforeUser = existing.filter { it is Message.User }.size
            val afterUser = merged.filter { it is Message.User }.size
            AppLogger.i("MsgDiag", "[setMessages] session=${sessionId.take(8)} " +
                "incoming=${newMessages.size} existing=${existing.size} merged=${merged.size} " +
                "beforeUser=$beforeUser afterUser=$afterUser " +
                "hasRestUserMsgs=$hasRestUserMsgs")
            current + (sessionId to merged)
        }
        newMessages.forEach { if (it.info is Message.Assistant) assistantMessageIds.add(it.info.id) }
        val partsMap = newMessages.associate { it.info.id to it.parts }
        _parts.update { current ->
            val merged = partsMap.mapValues { (messageId, incomingParts) ->
                val existingParts = current[messageId]
                if (existingParts != null) {
                    // 诊断：检查合并后文本长度是否回退
                    for (inc in incomingParts) {
                        if (inc is Part.Text) {
                            val ex = existingParts.find { it.id == inc.id }
                            if (ex is Part.Text && ex.text.length > inc.text.length) {
                                AppLogger.w(TAG, "[setMessages] t=$thread msg=${messageId.take(8)} " +
                                    "part=${inc.id.take(8)} SSE=${ex.text.length} > REST=${inc.text.length} " +
                                    "→ keeping SSE text")
                            }
                        }
                    }
                    mergePartsList(existingParts, incomingParts)
                } else {
                    incomingParts
                }
            }
            current + merged
        }
    }

    fun mergeMessages(sessionId: String, newMessages: List<MessageWithParts>) {
        val incoming = newMessages.map { it.info }.sortedBy { m -> m.time.created }
        // 先更新 parts，再更新 messages。这避免了 combine flow 看到
        // 新消息却没有对应 part 时的闪烁（P5-3 过滤器会临时移除它们）。
        _parts.update { currentParts ->
            val existingKeys = currentParts.keys
            val newParts = newMessages
                .filter { it.info.id !in existingKeys }
                .associate { it.info.id to it.parts }
            currentParts + newParts
        }
        newMessages.forEach { if (it.info is Message.Assistant) assistantMessageIds.add(it.info.id) }
        _messages.update { current ->
            val existing = current[sessionId] ?: emptyList()
            val existingById = existing.associateBy { it.id }
            current + (sessionId to incoming.map { newMsg -> existingById[newMsg.id] ?: newMsg })
        }
    }

    /**
     * 用 REST 数据替换会话的所有消息和 part。
     * 与 [mergeMessages] 不同，此处将 REST 视为真相源，
     * 覆盖任何现有本地数据。用于 SSE 重连恢复。
     *
     * 仅 SSE 才有的消息（不在 REST 响应中）会被保留，以处理
     * REST 快照与新 SSE 连接建立之间的时间窗口。
     */
    fun replaceMessages(sessionId: String, newMessages: List<MessageWithParts>) {
        @Suppress("DEPRECATION")
        val thread = Thread.currentThread().id
        _messages.update { current ->
            val existing = current[sessionId] ?: emptyList()
            val incomingById = newMessages.associateBy { it.info.id }
            val merged = (existing + newMessages.map { it.info })
                .distinctBy { it.id }
                .map { msg -> incomingById[msg.id]?.info ?: msg }
                .sortedBy { it.time.created }
            current + (sessionId to merged)
        }
        newMessages.forEach { if (it.info is Message.Assistant) assistantMessageIds.add(it.info.id) }
        val partsMap = newMessages.associate { it.info.id to it.parts }
        _parts.update { current ->
            val merged = partsMap.mapValues { (messageId, incomingParts) ->
                val existingParts = current[messageId]
                if (existingParts != null) {
                    // 诊断：检查合并后文本长度是否回退
                    for (inc in incomingParts) {
                        if (inc is Part.Text) {
                            val ex = existingParts.find { it.id == inc.id }
                            if (ex is Part.Text && ex.text.length > inc.text.length) {
                                AppLogger.w(TAG, "[replaceMessages] t=$thread msg=${messageId.take(8)} " +
                                    "part=${inc.id.take(8)} SSE=${ex.text.length} > REST=${inc.text.length} " +
                                    "→ keeping SSE text")
                            }
                        }
                    }
                    mergePartsList(existingParts, incomingParts)
                } else {
                    incomingParts
                }
            }
            current + merged
        }
    }

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
                    AppLogger.i("UnreadDiag", "[markIdle] session=${sessionId.take(12)} msg=${msg.id.take(12)} -> completed")
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

