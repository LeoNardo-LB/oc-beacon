package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.SseEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 处理 Part 生命周期：part updated、delta 和 removed。
 *
 * 委托给共享的 [MessageEventHandler] 状态存储，它持有
 * 紧密耦合的 `_parts` 状态、48ms delta 批处理管线，
 * 以及 [MessageEventHandler.handleMessagePartUpdated] 查询的
 * `assistantMessageIds` 集合。
 */
@Singleton
class MessagePartHandler @Inject constructor(
    private val store: MessageEventHandler
) : SseEventHandler {

    override fun handle(event: SseEvent, serverId: String): Boolean {
        return when (event) {
            is SseEvent.MessagePartUpdated -> { store.handleMessagePartUpdated(event); true }
            is SseEvent.MessagePartDelta -> { store.handleMessagePartDelta(event); true }
            is SseEvent.MessagePartRemoved -> { store.handleMessagePartRemoved(event); true }
            else -> false
        }
    }
}
