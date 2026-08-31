package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.data.repository.DshQueueStore
import dev.leonardo.ocbeacon.domain.model.SseEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DSH 排队收件箱事件处理器——session/queue 帧映射的 [SseEvent.QueueSnapshot] 写入
 * [DshQueueStore]（整快照 last-wins）。空集删键由 store 语义承担；subscribed
 * 清空重推由 DshEventMapper 发空快照承担（2026-09-01 QueueDock）。
 */
@Singleton
class DshQueueHandler @Inject constructor(
    private val store: DshQueueStore,
) : SseEventHandler {

    override fun handle(event: SseEvent, serverId: String): Boolean = when (event) {
        is SseEvent.QueueSnapshot -> { store.applySnapshot(event.sessionId, event.items); true }
        else -> false
    }

    /** 释放单会话队列（SessionDeleted 级联——EventDispatcher 调用）。 */
    fun clearForSession(sessionId: String) = store.clearForSession(sessionId)
}