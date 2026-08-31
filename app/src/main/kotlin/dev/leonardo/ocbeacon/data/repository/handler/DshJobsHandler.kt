package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.data.repository.DshJobsStore
import dev.leonardo.ocbeacon.domain.model.SseEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DSH 后台任务事件处理器——session/jobs 帧映射的 [SseEvent.JobsSnapshot] 写入
 * [DshJobsStore]（整快照 last-wins）。空集删键由 store 语义承担；subscribed
 * 清空重推由 DshEventMapper 发空快照承担。
 */
@Singleton
class DshJobsHandler @Inject constructor(
    private val store: DshJobsStore,
) : SseEventHandler {

    override fun handle(event: SseEvent, serverId: String): Boolean = when (event) {
        is SseEvent.JobsSnapshot -> { store.applySnapshot(event.sessionId, event.jobs); true }
        else -> false
    }

    /** 释放单会话任务（内存泄漏修复 #89 同款——EventDispatcher 级联调用）。 */
    fun clearForSession(sessionId: String) = store.clearForSession(sessionId)
}
