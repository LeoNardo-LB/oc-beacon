package dev.leonardo.octether.data.api.sse.parsers

import android.util.Log
import dev.leonardo.octether.domain.model.SseEvent
import kotlinx.serialization.json.*

private const val TAG = "SseClient"

/**
 * Parses miscellaneous events that don't fit other categories:
 * - todo.updated
 * - workspace.ready, workspace.failed
 * - file.edited, file.watcher.updated
 * - mcp.tools.changed
 * - installation.updated, installation.update_available
 * - worktree.ready, worktree.failed
 * - server.connected, server.heartbeat
 */
class MiscEventParser : SseEventParser {

    private val handledTypes = setOf(
        "server.connected", "server.heartbeat",
        "todo.updated",
        "workspace.ready", "workspace.failed",
        "file.edited", "file.watcher.updated",
        "mcp.tools.changed",
        "installation.updated", "installation.update_available",
        "worktree.ready", "worktree.failed"
    )

    override fun canParse(eventType: String): Boolean = eventType in handledTypes

    override fun parse(eventType: String, props: JsonObject): SseEvent? {
        return try {
            when (eventType) {
                "server.connected" -> SseEvent.ServerConnected
                "server.heartbeat" -> SseEvent.ServerHeartbeat

                "todo.updated" -> {
                    val sessionId = props.str("sessionID")
                    val todosArr = props["todos"]?.jsonArray
                    val todos = todosArr?.map { tElement ->
                        val tObj = tElement.jsonObject
                        SseEvent.TodoUpdated.Todo(
                            content = tObj.str("content"),
                            status = tObj.str("status", "pending"),
                            priority = tObj.str("priority", "medium")
                        )
                    } ?: emptyList()
                    SseEvent.TodoUpdated(sessionId = sessionId, todos = todos)
                }

                "workspace.ready" -> {
                    SseEvent.WorkspaceReady(workspaceId = props.str("workspaceID"))
                }

                "workspace.failed" -> {
                    SseEvent.WorkspaceFailed(
                        workspaceId = props.str("workspaceID"),
                        error = props.strOrNull("error")
                    )
                }

                "file.edited" -> {
                    SseEvent.FileEdited(path = props.str("path"))
                }

                "file.watcher.updated" -> {
                    SseEvent.FileWatcherUpdated(path = props.str("path"))
                }

                "mcp.tools.changed" -> {
                    SseEvent.McpToolsChanged(server = props.str("server"))
                }

                "installation.updated" -> {
                    SseEvent.InstallationUpdated(version = props.str("version"))
                }

                "installation.update_available" -> {
                    SseEvent.InstallationUpdateAvailable(version = props.str("version"))
                }

                "worktree.ready" -> {
                    SseEvent.WorktreeReady(path = props.str("path"))
                }

                "worktree.failed" -> {
                    SseEvent.WorktreeFailed(
                        path = props.str("path"),
                        error = props.strOrNull("error")
                    )
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse $eventType: ${e.message}", e)
            null
        }
    }

    private fun JsonObject.strOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
}
