package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.SseEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 处理 [SseEvent.MessageRemoved]。
 *
 * 委托给共享的 [MessageEventHandler] 状态存储，它移除消息
 * 并清除其 part 和 `assistantMessageIds` 条目。
 */
@Singleton
class MessageRemovedHandler @Inject constructor(
    private val store: MessageEventHandler
) : SseEventHandler {

    override fun handle(event: SseEvent, serverId: String): Boolean {
        return when (event) {
            is SseEvent.MessageRemoved -> { store.handleMessageRemoved(event); true }
            else -> false
        }
    }
}
