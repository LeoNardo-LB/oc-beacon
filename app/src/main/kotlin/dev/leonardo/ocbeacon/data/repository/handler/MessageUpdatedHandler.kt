package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.SseEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 处理 [SseEvent.MessageUpdated]。
 *
 * 委托给共享的 [MessageEventHandler] 状态存储，它更新
 * `_messages` map、跟踪 `assistantMessageIds`，
 * 并为用户消息播种 `_parts`。
 */
@Singleton
class MessageUpdatedHandler @Inject constructor(
    private val store: MessageEventHandler
) : SseEventHandler {

    override fun handle(event: SseEvent, serverId: String): Boolean {
        return when (event) {
            is SseEvent.MessageUpdated -> { store.handleMessageUpdated(event); true }
            else -> false
        }
    }
}
