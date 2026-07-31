package dev.leonardo.ocbeacon.data.api.sse.parsers

import android.util.Log
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.*

private const val TAG = "SseClient"

/**
 * Parses PTY and command events:
 * - pty.created, pty.updated, pty.deleted
 * - command.executed
 */
class PtyEventParser : SseEventParser {

    private val handledTypes = setOf(
        "pty.created", "pty.updated", "pty.deleted", "command.executed"
    )

    override fun canParse(eventType: String): Boolean = eventType in handledTypes

    override fun parse(eventType: String, props: JsonObject): SseEvent? {
        return try {
            when (eventType) {
                "pty.created" -> {
                    SseEvent.PtyCreated(
                        id = props.str("id"),
                        title = props.str("title"),
                        command = props.str("command"),
                        cwd = props.str("cwd")
                    )
                }

                "pty.updated" -> {
                    SseEvent.PtyUpdated(
                        id = props.str("id"),
                        title = props.str("title"),
                        command = props.str("command"),
                        status = props.str("status")
                    )
                }

                "pty.deleted" -> {
                    SseEvent.PtyDeleted(id = props.str("id"))
                }

                "command.executed" -> {
                    SseEvent.CommandExecuted(
                        name = props.str("name"),
                        sessionId = props.str("sessionID"),
                        arguments = props.str("arguments"),
                        messageId = props.str("messageID")
                    )
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse $eventType: ${e.message}", e)
            null
        }
    }
}
