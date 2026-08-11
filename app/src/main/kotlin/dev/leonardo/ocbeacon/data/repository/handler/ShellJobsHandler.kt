package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.data.repository.ShellJobsStore
import dev.leonardo.ocbeacon.domain.model.SseEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 后台 shell 事件处理器——将 session.shell.started/ended 写入 [ShellJobsStore]。
 */
@Singleton
class ShellJobsHandler @Inject constructor(
    private val shellJobsStore: ShellJobsStore
) : SseEventHandler {

    override fun handle(event: SseEvent, serverId: String): Boolean = when (event) {
        is SseEvent.ShellJobStarted -> {
            shellJobsStore.onShellStarted(event.info)
            true
        }
        is SseEvent.ShellJobEnded -> {
            shellJobsStore.onShellEnded(event.info, event.output)
            true
        }
        else -> false
    }
}
