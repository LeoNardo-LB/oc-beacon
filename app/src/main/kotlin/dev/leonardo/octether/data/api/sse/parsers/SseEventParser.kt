package dev.leonardo.octether.data.api.sse.parsers

import dev.leonardo.octether.domain.model.SseEvent
import kotlinx.serialization.json.JsonObject

/**
 * Strategy interface for parsing SSE events by type.
 * Each implementation handles a subset of event types.
 */
interface SseEventParser {
    /** Returns true if this parser handles the given event type. */
    fun canParse(eventType: String): Boolean

    /**
     * Parse the event properties into an [SseEvent].
     * Returns null if parsing fails or the event should be skipped.
     */
    fun parse(eventType: String, props: JsonObject): SseEvent?
}
