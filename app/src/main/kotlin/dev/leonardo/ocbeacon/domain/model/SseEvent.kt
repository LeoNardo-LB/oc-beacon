package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
// 注：保留 JsonElement 导入以保持向后兼容；V2 metadata 使用 Map<String, String>

/**
 * SSE 事件 —— 来自 Server-Sent Events 流的事件。
 * 客户端从 GET /global/event 或 GET /event 收到的所有事件。
 */
@Serializable
sealed class SseEvent {
    // 服务器事件
    @Serializable
    data object ServerConnected : SseEvent()

    @Serializable
    data object ServerHeartbeat : SseEvent()

    @Serializable
    data class ServerInstanceDisposed(val directory: String) : SseEvent()

    // 会话生命周期
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
        val status: dev.leonardo.ocbeacon.domain.model.SessionStatus
    ) : SseEvent()

    @Serializable
    data class SessionIdle(val sessionId: String) : SseEvent()

    @Serializable
    data class SessionError(
        val sessionId: String?,
        val error: String
    ) : SseEvent()

    // 消息事件
    @Serializable
    data class MessageUpdated(val info: Message) : SseEvent()

    @Serializable
    data class MessageRemoved(
        val sessionId: String,
        val messageId: String
    ) : SseEvent()

    // Part 事件 —— 流式内容
    @Serializable
    data class MessagePartUpdated(val part: Part) : SseEvent()

    @Serializable
    data class MessagePartDelta(
        val sessionId: String,
        val messageId: String,
        val partId: String,
        val field: String,  // 通常为 "text"
        val delta: String   // 要追加的新内容块
    ) : SseEvent()

    @Serializable
    data class MessagePartRemoved(
        val sessionId: String,
        val messageId: String,
        val partId: String
    ) : SseEvent()

    // 权限事件
    @Serializable
    data class PermissionAsked(
        val id: String,
        val sessionId: String,
        val permission: String,
        val patterns: List<String> = emptyList(),
        val metadata: Map<String, String>? = null,
        val always: Boolean = false,
        val tool: ToolRef? = null,
        /** 瞬态：sub-agent 来源标签（例如 "scout subagent"），不参与序列化。 */
        @kotlinx.serialization.Transient
        val sourceSessionTitle: String? = null
    ) : SseEvent()

    @Serializable
    data class PermissionReplied(
        val sessionId: String,
        val requestId: String
    ) : SseEvent()

    // 问题事件
    @Serializable
    data class QuestionAsked(
        val id: String,
        val sessionId: String,
        val questions: List<Question>,
        val tool: ToolRef? = null,
        /** 瞬态：sub-agent 来源标签，不参与序列化。 */
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

    // Todo 事件
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

    // VCS 事件
    @Serializable
    data class VcsBranchUpdated(val branch: String) : SseEvent()

    // LSP 事件
    @Serializable
    data object LspUpdated : SseEvent()

    // 项目事件
    @Serializable
    data class ProjectUpdated(val info: Project) : SseEvent()

    // ============ V2 新增事件 ============

    // 会话压缩
    @Serializable
    data class SessionCompacted(val sessionId: String) : SseEvent()

    // PTY 事件（使用简单字段以避免跨包依赖）
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

    // V2 后台 shell 命令事件（session.shell.started/ended）
    // 与 Pty（交互式终端）不同：非交互命令，输出捕获到文件。
    @Serializable
    data class ShellJobStarted(val info: ShellJob) : SseEvent()

    @Serializable
    data class ShellJobEnded(val info: ShellJob, val output: String? = null) : SseEvent()

    // 工作区事件
    @Serializable
    data class WorkspaceReady(val workspaceId: String) : SseEvent()

    @Serializable
    data class WorkspaceFailed(val workspaceId: String, val error: String? = null) : SseEvent()

    // 文件编辑事件
    @Serializable
    data class FileEdited(val path: String) : SseEvent()

    // MCP 工具变更
    @Serializable
    data class McpToolsChanged(val server: String) : SseEvent()

    // 命令执行完成
    @Serializable
    data class CommandExecuted(
        val name: String,
        val sessionId: String,
        val arguments: String = "",
        val messageId: String = ""
    ) : SseEvent()

    // 文件监听
    @Serializable
    data class FileWatcherUpdated(val path: String) : SseEvent()

    // 安装更新
    @Serializable
    data class InstallationUpdated(val version: String) : SseEvent()

    @Serializable
    data class InstallationUpdateAvailable(val version: String) : SseEvent()

    // Worktree 事件
    @Serializable
    data class WorktreeReady(val path: String) : SseEvent()

    @Serializable
    data class WorktreeFailed(val path: String, val error: String? = null) : SseEvent()

    // Session Next 事件 —— 细粒度实时状态
    @Serializable
    data class SessionNext(val event: SessionNextEvent) : SseEvent()
}

/**
 * 工具调用引用（用于 permission/question 事件）。
 */
@Serializable
data class ToolRef(
    @SerialName("messageID") val messageId: String,
    @SerialName("callID") val callId: String
)

/**
 * 文件差异 —— 表示文件变更。
 * 与服务器的 Snapshot.FileDiff 对应。
 */
@Serializable
data class FileDiff(
    val file: String,
    val before: String = "",
    val after: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String? = null // "added"、"deleted"、"modified"
)

/**
 * 项目 —— 表示一个 OpenCode 项目。
 * 服务器返回字段：id、worktree、vcs、name、icon、commands、time、sandboxes
 */
@Serializable
data class Project(
    val id: String = "",
    val worktree: String = "",
    val name: String? = null,
    val path: String = "", // 旧字段，可能缺失
    val vcs: String? = null,
    val directory: String? = null
) {
    /** 显示名称：显式 name，或 worktree 的最后一段路径，或 id */
    val displayName: String
        get() = name?.takeIf { it.isNotEmpty() }
            ?: worktree.takeIf { it.isNotEmpty() }?.let { dev.leonardo.ocbeacon.util.PathUtils.fileName(it.trimEnd('/', '\\')) }?.takeIf { it.isNotEmpty() }
            ?: path.takeIf { it.isNotEmpty() }?.let { dev.leonardo.ocbeacon.util.PathUtils.fileName(it.trimEnd('/', '\\')) }?.takeIf { it.isNotEmpty() }
            ?: id.take(8)
}


