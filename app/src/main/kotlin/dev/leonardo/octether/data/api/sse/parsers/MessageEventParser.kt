package dev.leonardo.octether.data.api.sse.parsers

import android.util.Log
import dev.leonardo.octether.domain.model.Message
import dev.leonardo.octether.domain.model.Part
import dev.leonardo.octether.domain.model.SseEvent
import kotlinx.serialization.json.*

private const val TAG = "SseClient"

/**
 * Parses message-related events:
 * - message.updated, message.removed
 * - message.part.updated, message.part.delta, message.part.removed
 */
class MessageEventParser(private val json: Json) : SseEventParser {

    private val handledTypes = setOf(
        "message.updated", "message.removed",
        "message.part.updated", "message.part.delta", "message.part.removed"
    )

    override fun canParse(eventType: String): Boolean = eventType in handledTypes

    override fun parse(eventType: String, props: JsonObject): SseEvent? {
        return try {
            when (eventType) {
                "message.updated" -> {
                    val infoObj = props["info"]?.jsonObject ?: return null
                    val message = parseMessage(infoObj) ?: return null
                    SseEvent.MessageUpdated(info = message)
                }

                "message.removed" -> {
                    val sessionId = props.str("sessionID")
                    val messageId = props.str("messageID")
                    SseEvent.MessageRemoved(sessionId = sessionId, messageId = messageId)
                }

                "message.part.updated" -> {
                    val partObj = props["part"]?.jsonObject ?: return null
                    val part = parsePart(partObj) ?: return null
                    SseEvent.MessagePartUpdated(part = part)
                }

                "message.part.delta" -> {
                    val sessionId = props.str("sessionID")
                    val messageId = props.str("messageID")
                    val partId = props.str("partID")
                    val field = props.str("field", "text")
                    val delta = props.str("delta")
                    SseEvent.MessagePartDelta(
                        sessionId = sessionId,
                        messageId = messageId,
                        partId = partId,
                        field = field,
                        delta = delta
                    )
                }

                "message.part.removed" -> {
                    val sessionId = props.str("sessionID")
                    val messageId = props.str("messageID")
                    val partId = props.str("partID")
                    SseEvent.MessagePartRemoved(
                        sessionId = sessionId,
                        messageId = messageId,
                        partId = partId
                    )
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse $eventType: ${e.message}", e)
            null
        }
    }

    private fun parseMessage(obj: JsonObject): Message? {
        val role = obj["role"]?.jsonPrimitive?.content ?: return null
        return when (role) {
            "user" -> json.decodeFromJsonElement<Message.User>(obj)
            "assistant" -> json.decodeFromJsonElement<Message.Assistant>(obj)
            else -> {
                Log.w(TAG, "Unknown message role: $role")
                null
            }
        }
    }

    private fun parsePart(obj: JsonObject): Part? {
        val type = obj["type"]?.jsonPrimitive?.content ?: return null
        return try {
            when (type) {
                "text" -> json.decodeFromJsonElement<Part.Text>(obj)
                "reasoning" -> json.decodeFromJsonElement<Part.Reasoning>(obj)
                "tool" -> json.decodeFromJsonElement<Part.Tool>(obj)
                "step-start" -> json.decodeFromJsonElement<Part.StepStart>(obj)
                "step-finish" -> json.decodeFromJsonElement<Part.StepFinish>(obj)
                "file" -> json.decodeFromJsonElement<Part.File>(obj)
                "snapshot" -> json.decodeFromJsonElement<Part.Snapshot>(obj)
                "patch" -> json.decodeFromJsonElement<Part.Patch>(obj)
                "subtask" -> json.decodeFromJsonElement<Part.Subtask>(obj)
                "compaction" -> json.decodeFromJsonElement<Part.Compaction>(obj)
                "retry" -> json.decodeFromJsonElement<Part.Retry>(obj)
                "abort" -> json.decodeFromJsonElement<Part.Abort>(obj)
                "agent" -> json.decodeFromJsonElement<Part.Agent>(obj)
                "session-turn" -> json.decodeFromJsonElement<Part.SessionTurn>(obj)
                else -> {
                    Log.w(TAG, "Unknown part type: $type")
                    Part.Unknown(
                        id = obj.str("id"),
                        sessionId = obj.str("sessionID"),
                        messageId = obj.str("messageID")
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse part type=$type: ${e.message}", e)
            null
        }
    }
}
