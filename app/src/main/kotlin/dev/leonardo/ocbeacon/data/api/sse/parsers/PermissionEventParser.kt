package dev.leonardo.ocbeacon.data.api.sse.parsers

import android.util.Log
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.ToolRef
import kotlinx.serialization.json.*

private const val TAG = "SseClient"

/**
 * Parses permission events:
 * - permission.asked, permission.replied
 */
class PermissionEventParser : SseEventParser {

    private val handledTypes = setOf("permission.asked", "permission.replied")

    override fun canParse(eventType: String): Boolean = eventType in handledTypes

    override fun parse(eventType: String, props: JsonObject): SseEvent? {
        return try {
            when (eventType) {
                "permission.asked" -> {
                    val id = props.str("id")
                    val sessionId = props.str("sessionID")
                    val permission = props.str("permission")
                    val patterns = props["patterns"]?.jsonArray
                        ?.map { it.jsonPrimitive.content } ?: emptyList()
                    // V2: always is Boolean; fallback to V1 List<String> for backward compat
                    val always = props["always"]?.let { el ->
                        when {
                            el is JsonPrimitive -> el.booleanOrNull ?: false
                            el is JsonArray -> el.isNotEmpty()
                            else -> false
                        }
                    } ?: false
                    val metadata = props["metadata"]?.jsonObject?.let { obj ->
                        obj.mapValues { (_, v) -> v.jsonPrimitive.contentOrNull ?: v.toString() }
                    }
                    val toolRef = props["tool"]?.jsonObject?.let { toolObj ->
                        ToolRef(
                            messageId = toolObj.str("messageID"),
                            callId = toolObj.str("callID")
                        )
                    }

                    Log.i(TAG, "Permission asked: $permission for session $sessionId")
                    SseEvent.PermissionAsked(
                        id = id,
                        sessionId = sessionId,
                        permission = permission,
                        patterns = patterns,
                        always = always,
                        metadata = metadata,
                        tool = toolRef
                    )
                }

                "permission.replied" -> {
                    val sessionId = props.str("sessionID")
                    val requestId = props.str("requestID")
                    SseEvent.PermissionReplied(sessionId = sessionId, requestId = requestId)
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse $eventType: ${e.message}", e)
            null
        }
    }
}
