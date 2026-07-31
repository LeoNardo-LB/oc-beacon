package dev.leonardo.ocbeacon.data.api.sse.parsers

import android.util.Log
import dev.leonardo.ocbeacon.domain.model.SessionNextEvent
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.*

private const val TAG = "SseClient"

/**
 * Parses session.next.* events via prefix matching.
 * Delegates to kotlinx.serialization with type discriminator.
 */
class SessionNextEventParser(private val json: Json) : SseEventParser {

    private val prefix = "session.next."

    override fun canParse(eventType: String): Boolean = eventType.startsWith(prefix)

    override fun parse(eventType: String, props: JsonObject): SseEvent? {
        val nextEvent = parseSessionNextEvent(eventType, props)
        return SseEvent.SessionNext(nextEvent)
    }

    /**
     * Parse a session.next.* event from type string and properties.
     * Called when the SSE event type starts with "session.next.".
     * Uses kotlinx.serialization Json to decode into the appropriate SessionNextEvent variant.
     */
    fun parseSessionNextEvent(type: String, props: JsonObject): SessionNextEvent {
        return try {
            // Inject the type into props so the discriminator can select the correct variant
            val propsWithType = JsonObject(props + ("type" to JsonPrimitive(type)))
            val result = json.decodeFromString<SessionNextEvent>(propsWithType.toString())
            // Serializer routes unknown types to Unknown but doesn't populate rawType from "type"
            if (result is SessionNextEvent.Unknown && result.rawType.isEmpty()) {
                result.copy(rawType = type, rawJson = props.toString())
            } else {
                result
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse session.next event: $type — ${e.message}")
            SessionNextEvent.Unknown(rawType = type, rawJson = props.toString())
        }
    }
}
