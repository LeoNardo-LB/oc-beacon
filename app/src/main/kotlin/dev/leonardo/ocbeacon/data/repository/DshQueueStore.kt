package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.QueuedInboxItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DSH 会话排队收件箱状态容器（单一真相源，对齐官方 queueMirror；2026-09-01 QueueDock）。
 *
 * 语义（官方 dsh-client-runtime SessionQueueMirror，client.js:7026-7080）：
 * - session/queue 帧整快照 last-wins 整替换（非合并）；
 * - 空集 = 删键（该会话队列清空）；
 * - subscribed 重连：先清空（queueMirror.reset），服务器随后重推整快照。
 *
 * 瞬态数据：不入 Room/历史、不重放（mapper 仅帧面投递）——防替换/历史折叠
 * 语义与 DshJobsStore 同款（d9fc169b 先例）。
 */
@Singleton
class DshQueueStore @Inject constructor() {

    private val _queueBySession = MutableStateFlow<Map<String, List<QueuedInboxItem>>>(emptyMap())

    /** sessionId → 排队收件箱整快照（last-wins）。 */
    val queueBySession: StateFlow<Map<String, List<QueuedInboxItem>>> = _queueBySession.asStateFlow()

    /** 指定会话的排队项（原始快照序——帧序即 FIFO 排队序）。 */
    fun queueFor(sessionId: String): List<QueuedInboxItem> = _queueBySession.value[sessionId].orEmpty()

    /** 整快照 last-wins：空集删键，非空整替换。 */
    fun applySnapshot(sessionId: String, items: List<QueuedInboxItem>) {
        _queueBySession.update { all ->
            if (items.isEmpty()) all - sessionId else all + (sessionId to items)
        }
    }

    fun clearForSession(sessionId: String) {
        _queueBySession.update { all -> all - sessionId }
    }

    fun clear() {
        _queueBySession.value = emptyMap()
    }
}