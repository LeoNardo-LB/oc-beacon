package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.SseEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles [SseEvent.MessageUpdated].
 *
 * Delegates to the shared [MessageEventHandler] state store, which updates the
 * `_messages` map, tracks `assistantMessageIds`, and seeds `_parts` for user
 * messages.
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
