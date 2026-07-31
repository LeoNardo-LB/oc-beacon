package dev.leonardo.octether.data.repository.handler

import dev.leonardo.octether.domain.model.SseEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles the Part lifecycle: part updated, delta, and removed.
 *
 * Delegates to the shared [MessageEventHandler] state store, which owns the
 * tightly-coupled `_parts` state plus the 48ms delta-batching pipeline and the
 * `assistantMessageIds` set consulted by [MessageEventHandler.handleMessagePartUpdated].
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
