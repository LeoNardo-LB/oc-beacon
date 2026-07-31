package dev.leonardo.octether.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
// Note: JsonElement import kept for backward compat; V2 metadata uses Map<String, String>

/**
 * SSE Event - events from Server-Sent Events stream.
 * All events that the client receives from GET /global/event or GET /event.
 */
@Serializable
sealed class SseEvent {
    // Server events
    @Serializable
    data object ServerConnected : SseEvent()

    @Serializable
    data object ServerHeartbeat : SseEvent()

    @Serializable
    data class ServerInstanceDisposed(val directory: String) : SseEvent()

    // Session lifecycle
    @Serializable
    data class SessionCreated(val info: Session) : SseEvent()

    @Serializable
    data class SessionUpdated(val info: Session) : SseEvent()

    @Serializable
    data class SessionDeleted(val info: Session) : SseEvent()

    @Serializable
    data class SessionDiff(
        val sessionId: String,
        val diff: List<FileDiff>
    ) : SseEvent()

    @Serializable
    data class SessionStatus(
        val sessionId: String,
        val status: dev.leonardo.octether.domain.model.SessionStatus
    ) : SseEvent()

    @Serializable
    data class SessionIdle(val sessionId: String) : SseEvent()

    @Serializable
    data class SessionError(
        val sessionId: String?,
        val error: String
    ) : SseEvent()

    // Message events
    @Serializable
    data class MessageUpdated(val info: Message) : SseEvent()

    @Serializable
    data class MessageRemoved(
        val sessionId: String,
        val messageId: String
    ) : SseEvent()

    // Part events - the streaming content
    @Serializable
    data class MessagePartUpdated(val part: Part) : SseEvent()

    @Serializable
    data class MessagePartDelta(
        val sessionId: String,
        val messageId: String,
        val partId: String,
        val field: String,  // Usually "text"
        val delta: String   // The new chunk to append
    ) : SseEvent()

    @Serializable
    data class MessagePartRemoved(
        val sessionId: String,
        val messageId: String,
        val partId: String
    ) : SseEvent()

    // Permission events
    @Serializable
    data class PermissionAsked(
        val id: String,
        val sessionId: String,
        val permission: String,
        val patterns: List<String> = emptyList(),
        val metadata: Map<String, String>? = null,
        val always: Boolean = false,
        val tool: ToolRef? = null,
        /** Transient: sub-agent source label (e.g. "scout subagent"), not serialized. */
        @kotlinx.serialization.Transient
        val sourceSessionTitle: String? = null
    ) : SseEvent()

    @Serializable
    data class PermissionReplied(
        val sessionId: String,
        val requestId: String
    ) : SseEvent()

    // Question events
    @Serializable
    data class QuestionAsked(
        val id: String,
        val sessionId: String,
        val questions: List<Question>,
        val tool: ToolRef? = null,
        /** Transient: sub-agent source label, not serialized. */
        @kotlinx.serialization.Transient
        val sourceSessionTitle: String? = null
    ) : SseEvent() {
        @Serializable
        data class Question(
            val header: String,
            val question: String,
            val multiple: Boolean = false,
            val custom: Boolean = true,
            val options: List<Option>
        )

        @Serializable
        data class Option(
            val label: String,
            val description: String
        )
    }

    @Serializable
    data class QuestionReplied(
        val sessionId: String,
        val requestId: String
    ) : SseEvent()

    @Serializable
    data class QuestionRejected(
        val sessionId: String,
        val requestId: String
    ) : SseEvent()

    // Todo events
    @Serializable
    data class TodoUpdated(
        val sessionId: String,
        val todos: List<Todo>
    ) : SseEvent() {
        @Serializable
        data class Todo(
            val content: String,
            val status: String,
            val priority: String
        )
    }

    // VCS events
    @Serializable
    data class VcsBranchUpdated(val branch: String) : SseEvent()

    // LSP events
    @Serializable
    data object LspUpdated : SseEvent()

    // Project events
    @Serializable
    data class ProjectUpdated(val info: Project) : SseEvent()

    // ============ V2 New Events ============

    // Session compaction
    @Serializable
    data class SessionCompacted(val sessionId: String) : SseEvent()

    // PTY events (using simple fields to avoid cross-package dependency)
    @Serializable
    data class PtyCreated(
        val id: String,
        val title: String = "",
        val command: String = "",
        val cwd: String = ""
    ) : SseEvent()

    @Serializable
    data class PtyUpdated(
        val id: String,
        val title: String = "",
        val command: String = "",
        val status: String = ""
    ) : SseEvent()

    @Serializable
    data class PtyDeleted(val id: String) : SseEvent()

    // Workspace events
    @Serializable
    data class WorkspaceReady(val workspaceId: String) : SseEvent()

    @Serializable
    data class WorkspaceFailed(val workspaceId: String, val error: String? = null) : SseEvent()

    // File edit event
    @Serializable
    data class FileEdited(val path: String) : SseEvent()

    // MCP tools changed
    @Serializable
    data class McpToolsChanged(val server: String) : SseEvent()

    // Command execution completed
    @Serializable
    data class CommandExecuted(
        val name: String,
        val sessionId: String,
        val arguments: String = "",
        val messageId: String = ""
    ) : SseEvent()

    // File watcher
    @Serializable
    data class FileWatcherUpdated(val path: String) : SseEvent()

    // Installation updates
    @Serializable
    data class InstallationUpdated(val version: String) : SseEvent()

    @Serializable
    data class InstallationUpdateAvailable(val version: String) : SseEvent()

    // Worktree events
    @Serializable
    data class WorktreeReady(val path: String) : SseEvent()

    @Serializable
    data class WorktreeFailed(val path: String, val error: String? = null) : SseEvent()

    // Session Next events — fine-grained real-time status
    @Serializable
    data class SessionNext(val event: SessionNextEvent) : SseEvent()
}

/**
 * Reference to a tool call (used in permission/question events).
 */
@Serializable
data class ToolRef(
    @SerialName("messageID") val messageId: String,
    @SerialName("callID") val callId: String
)

/**
 * File Diff - represents changes to a file.
 * Matches Snapshot.FileDiff from the server.
 */
@Serializable
data class FileDiff(
    val file: String,
    val before: String = "",
    val after: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String? = null // "added", "deleted", "modified"
)

/**
 * Project - represents an OpenCode project.
 * Server returns: id, worktree, vcs, name, icon, commands, time, sandboxes
 */
@Serializable
data class Project(
    val id: String = "",
    val worktree: String = "",
    val name: String? = null,
    val path: String = "", // legacy, may be absent
    val vcs: String? = null,
    val directory: String? = null
) {
    /** Display name: explicit name, or last path segment of worktree, or id */
    val displayName: String
        get() = name?.takeIf { it.isNotEmpty() }
            ?: worktree.takeIf { it.isNotEmpty() }?.let { dev.leonardo.octether.util.PathUtils.fileName(it.trimEnd('/', '\\')) }?.takeIf { it.isNotEmpty() }
            ?: path.takeIf { it.isNotEmpty() }?.let { dev.leonardo.octether.util.PathUtils.fileName(it.trimEnd('/', '\\')) }?.takeIf { it.isNotEmpty() }
            ?: id.take(8)
}


