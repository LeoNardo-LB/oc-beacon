package dev.leonardo.octether.data.repository.handler

import dev.leonardo.octether.domain.model.SseEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles [SseEvent.MessageRemoved].
 *
 * Delegates to the shared [MessageEventHandler] state store, which removes the
 * message and clears its parts and `assistantMessageIds` entry.
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
