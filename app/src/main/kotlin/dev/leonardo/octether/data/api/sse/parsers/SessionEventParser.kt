package dev.leonardo.octether.data.api.sse.parsers

import android.util.Log
import dev.leonardo.octether.domain.model.FileDiff
import dev.leonardo.octether.domain.model.Session
import dev.leonardo.octether.domain.model.SessionStatus
import dev.leonardo.octether.domain.model.SseEvent
import kotlinx.serialization.json.*

private const val TAG = "SseClient"

/**
 * Parses session lifecycle events:
 * - session.status, session.idle, session.created, session.updated, session.deleted
 * - session.error, session.diff, session.compacted
 * - vcs.branch.updated, project.updated, lsp.updated
 */
class SessionEventParser(private val json: Json) : SseEventParser {

    private val handledTypes = setOf(
        "session.status", "session.idle", "session.created", "session.updated",
        "session.deleted", "session.error", "session.diff", "session.compacted",
        "vcs.branch.updated", "project.updated", "lsp.updated"
    )

    override fun canParse(eventType: String): Boolean = eventType in handledTypes

    override fun parse(eventType: String, props: JsonObject): SseEvent? {
        return try {
            when (eventType) {
                "session.status" -> {
                    val sessionId = props.str("sessionID")
                    val statusObj = props["status"]?.jsonObject
                    val statusType = statusObj?.get("type")?.jsonPrimitive?.content ?: "idle"

                    val status = when (statusType) {
                        "idle" -> SessionStatus.Idle
                        "busy" -> SessionStatus.Busy
                        "retry" -> SessionStatus.Retry(
                            attempt = statusObj?.get("attempt")?.jsonPrimitive?.int ?: 0,
                            message = statusObj?.get("message")?.jsonPrimitive?.content ?: "",
                            next = statusObj?.get("next")?.jsonPrimitive?.long ?: 0
                        )
                        else -> SessionStatus.Idle
                    }

                    Log.i(TAG, "Session $sessionId status -> $statusType")
                    SseEvent.SessionStatus(sessionId = sessionId, status = status)
                }

                "session.idle" -> {
                    val sessionId = props.str("sessionID")
                    Log.i(TAG, "Session $sessionId idle")
                    SseEvent.SessionIdle(sessionId = sessionId)
                }

                "session.created" -> {
                    val infoObj = props["info"]?.jsonObject ?: props
                    val info = json.decodeFromJsonElement<Session>(infoObj)
                    SseEvent.SessionCreated(info)
                }

                "session.updated" -> {
                    val infoObj = props["info"]?.jsonObject ?: props
                    val info = json.decodeFromJsonElement<Session>(infoObj)
                    SseEvent.SessionUpdated(info)
                }

                "session.deleted" -> {
                    val infoObj = props["info"]?.jsonObject ?: props
                    val info = json.decodeFromJsonElement<Session>(infoObj)
                    SseEvent.SessionDeleted(info)
                }

                "session.error" -> {
                    val sessionId = props["sessionID"]?.jsonPrimitive?.content
                    val error = props.str("error", "Unknown error")
                    SseEvent.SessionError(sessionId = sessionId, error = error)
                }

                "session.diff" -> {
                    val sessionId = props.str("sessionID")
                    val diffArr = props["diff"]?.jsonArray
                    val diffs = diffArr?.map { json.decodeFromJsonElement<FileDiff>(it) } ?: emptyList()
                    SseEvent.SessionDiff(sessionId = sessionId, diff = diffs)
                }

                "session.compacted" -> {
                    SseEvent.SessionCompacted(sessionId = props.str("sessionID"))
                }

                "vcs.branch.updated" -> {
                    val branch = props.str("branch")
                    SseEvent.VcsBranchUpdated(branch = branch)
                }

                "project.updated" -> {
                    val infoObj = props["info"]?.jsonObject ?: props
                    val info = json.decodeFromJsonElement<dev.leonardo.octether.domain.model.Project>(infoObj)
                    SseEvent.ProjectUpdated(info)
                }

                "lsp.updated" -> SseEvent.LspUpdated

                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse $eventType: ${e.message}", e)
            null
        }
    }
}
