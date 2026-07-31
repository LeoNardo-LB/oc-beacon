package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.SseEvent

/**
 * Strategy interface for handling SSE events by category.
 * Each handler processes a subset of SseEvent types and updates its own state.
 */
interface SseEventHandler {
    /**
     * Handle the given event, updating internal state as needed.
     * @param event The SSE event to process
     * @param serverId The server this event came from
     * @return true if this handler recognized and processed the event
     */
    fun handle(event: SseEvent, serverId: String): Boolean
}
