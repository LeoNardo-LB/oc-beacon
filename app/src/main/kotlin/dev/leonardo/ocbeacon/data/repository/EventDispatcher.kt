package dev.leonardo.ocbeacon.data.repository

import android.util.Log
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.repository.handler.*
import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionNextEvent
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

private const val TAG = "EventDispatcher"

/**
 * Event Dispatcher - replaces the monolithic EventReducer.
 *
 * Delegates SSE events to registered [SseEventHandler] instances.
 * Exposes read-only StateFlows aggregated from handlers.
 * Handles cross-cutting concerns (e.g. SessionDeleted cascading cleanup,
 * CommandExecuted session status reset).
 */
@Singleton
class EventDispatcher @Inject constructor(
    private val sessionHandler: SessionEventHandler,
    private val messageHandler: MessageEventHandler,
    private val messagePartHandler: MessagePartHandler,
    private val messageUpdatedHandler: MessageUpdatedHandler,
    private val messageRemovedHandler: MessageRemovedHandler,
    private val permissionHandler: PermissionEventHandler,
    private val questionHandler: QuestionEventHandler,
    private val miscHandler: MiscEventHandler,
    private val sessionNextHandler: SessionNextEventHandler,
    private val sessionStateService: SessionStateService,
) {
    init {
        // SessionStateService callbacks — wired here to break the circular dep
        // (EventDispatcher ← SessionStateService via Provider, but callbacks
        // need messageHandler which lives in EventDispatcher's scope).
        sessionStateService.incompleteChecker = IncompleteAssistantChecker { sessionId ->
            hasIncompleteAssistant(sessionId)
        }
        sessionStateService.directoryResolver = DirectoryResolver { sessionId ->
            sessionHandler.sessions.value.find { it.id == sessionId }?.directory
        }
        sessionStateService.messageForceCompleter = MessageForceCompleter { sessionId ->
            messageHandler.markSessionIdle(sessionId)
        }
        sessionStateService.messageRefresher = MessageRefresher { sessionId, messages ->
            messageHandler.replaceMessages(sessionId, messages)
        }
    }

    /**
     * Tracks which serverId "owns" each session for SSE event processing.
     *
     * When two server configs point to the same backend (same OpenCode serve
     * instance), both SSE connections deliver identical global events. Without
     * ownership tracking, append-style events like [SseEvent.MessagePartDelta]
     * would be applied twice, doubling the streaming text output.
     *
     * The first server to deliver an event for a session claims ownership.
     * Events for that session from any other serverId are skipped.
     * Ownership is released on [clearForServer], [clearAll], or [SseEvent.SessionDeleted].
     */
    private val streamingSessionOwners = java.util.concurrent.ConcurrentHashMap<String, String>()

    // ============ Event Handler Registry (Open/Closed Principle) ============
    // Maps each SseEvent subclass to its single responsible handler.
    // To support a new event domain: add a bind() call below. processEvent itself
    // never changes — it just looks up this map. This replaces the previous
    // broadcast model where every event was sent to all 6 handlers and each
    // handler filtered internally via its own `when` block.
    private val registry: Map<KClass<out SseEvent>, SseEventHandler> = buildRegistry()

    private fun buildRegistry(): Map<KClass<out SseEvent>, SseEventHandler> {
        val map = mutableMapOf<KClass<out SseEvent>, SseEventHandler>()
        fun bind(handler: SseEventHandler, vararg events: KClass<out SseEvent>) {
            for (e in events) map[e] = handler
        }
        // Session lifecycle + server connection events → SessionEventHandler
        bind(
            sessionHandler,
            SseEvent.ServerConnected::class, SseEvent.ServerHeartbeat::class,
            SseEvent.ServerInstanceDisposed::class,
            SseEvent.SessionCreated::class, SseEvent.SessionUpdated::class,
            SseEvent.SessionDeleted::class, SseEvent.SessionStatus::class,
            SseEvent.SessionIdle::class, SseEvent.SessionError::class,
            SseEvent.SessionDiff::class, SseEvent.SessionCompacted::class,
            SseEvent.VcsBranchUpdated::class, SseEvent.ProjectUpdated::class
        )
        // Messages → per-sub-event handlers. They share the MessageEventHandler
        // state store (injected) but are registered independently so each
        // message event type routes to its focused handler.
        bind(
            messageUpdatedHandler,
            SseEvent.MessageUpdated::class
        )
        bind(
            messageRemovedHandler,
            SseEvent.MessageRemoved::class
        )
        bind(
            messagePartHandler,
            SseEvent.MessagePartUpdated::class, SseEvent.MessagePartDelta::class,
            SseEvent.MessagePartRemoved::class
        )
        // Permission → PermissionEventHandler
        bind(
            permissionHandler,
            SseEvent.PermissionAsked::class, SseEvent.PermissionReplied::class
        )
        // Question → QuestionEventHandler
        bind(
            questionHandler,
            SseEvent.QuestionAsked::class, SseEvent.QuestionReplied::class,
            SseEvent.QuestionRejected::class
        )
        // Misc (todo, command, pty, workspace, file, vcs, install, lsp) → MiscEventHandler
        bind(
            miscHandler,
            SseEvent.TodoUpdated::class, SseEvent.CommandExecuted::class,
            SseEvent.PtyCreated::class, SseEvent.PtyUpdated::class, SseEvent.PtyDeleted::class,
            SseEvent.WorkspaceReady::class, SseEvent.WorkspaceFailed::class,
            SseEvent.FileEdited::class, SseEvent.McpToolsChanged::class,
            SseEvent.FileWatcherUpdated::class,
            SseEvent.InstallationUpdated::class, SseEvent.InstallationUpdateAvailable::class,
            SseEvent.WorktreeReady::class, SseEvent.WorktreeFailed::class,
            SseEvent.LspUpdated::class
        )
        // SessionNext → SessionNextEventHandler
        bind(sessionNextHandler, SseEvent.SessionNext::class)
        return map
    }

    // ============ Public State (read-only) ============

    val serverSessions: StateFlow<Map<String, Set<String>>> get() = sessionHandler.serverSessions
    val sessions: StateFlow<List<Session>> get() = sessionHandler.sessions
    /** Facade over [SessionStateService.statusFlow] — the single source of truth for session status. */
    val sessionStatuses: StateFlow<Map<String, SessionStatus>> get() = sessionStateService.statusFlow
    val messages: StateFlow<Map<String, List<Message>>> get() = messageHandler.messages
    val parts: StateFlow<Map<String, List<Part>>> get() = messageHandler.parts
    val sessionDiffs: StateFlow<Map<String, List<FileDiff>>> get() = sessionHandler.sessionDiffs
    val permissions: StateFlow<Map<String, List<SseEvent.PermissionAsked>>> get() = permissionHandler.permissions
    val questions: StateFlow<Map<String, List<SseEvent.QuestionAsked>>> get() = questionHandler.questions
    val todos: StateFlow<Map<String, List<SseEvent.TodoUpdated.Todo>>> get() = miscHandler.todos
    val vcsBranch: StateFlow<String?> get() = sessionHandler.vcsBranch
    val projectInfo: StateFlow<Project?> get() = sessionHandler.projectInfo
    val lastUserMessageTime: StateFlow<Map<String, Long>> get() = sessionHandler.lastUserMessageTime

    // Session Next state
    val currentAgent: StateFlow<Map<String, String>> get() = sessionNextHandler.currentAgent
    val currentModel: StateFlow<Map<String, Pair<String, String>>> get() = sessionNextHandler.currentModel
    val activeToolProgress: StateFlow<Map<String, List<ToolProgressInfo>>> get() = sessionNextHandler.activeToolProgress
    val stepProgress: StateFlow<Map<String, StepProgressInfo>> get() = sessionNextHandler.stepProgress
    val compactionState: StateFlow<Map<String, CompactionStateInfo>> get() = sessionNextHandler.compactionState
    val shellState: StateFlow<Map<String, ShellStateInfo>> get() = sessionNextHandler.shellState
    val retryState: StateFlow<Map<String, Int>> get() = sessionNextHandler.retryState
    val gapDetected: StateFlow<Set<String>> get() = sessionNextHandler.gapDetected

    // ============ Event Processing ============

    /**
     * Process an SSE event by dispatching to all handlers.
     * Handles cross-cutting concerns after dispatch:
     * - SessionDeleted: cascades cleanup to all handlers for the deleted session
     * - CommandExecuted: resets session status to Idle
     *
     * Multi-server dedup: if two server configs point to the same backend,
     * only the first server to claim a session processes its events. Subsequent
     * events for the same session from a different serverId are skipped to
     * prevent doubled streaming output.
     */
    fun processEvent(event: SseEvent, serverId: String) {
        // Ownership check: prevent duplicate event processing when two SSE
        // connections deliver the same events (same backend, different configs).
        val sessionId = extractSessionId(event)
        if (sessionId != null) {
            val owner = streamingSessionOwners.putIfAbsent(sessionId, serverId)
            if (owner != null && owner != serverId) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Skipping duplicate ${event::class.simpleName} for session " +
                        "${sessionId.take(12)} from server=$serverId (owner=$owner)")
                }
                return
            }
        }

        // Registry dispatch: route event to its single registered handler (O(1) lookup).
        // Replaces the previous broadcast model where every event was sent to all 6
        // handlers and each filtered internally via its own `when` block.
        val handler = registry[event::class]
        if (handler != null) {
            handler.handle(event, serverId)
        } else if (BuildConfig.DEBUG) {
            Log.w(TAG, "No handler registered for ${event::class.simpleName}")
        }
        forwardToSessionStateService(event)

        // Cross-handler: SessionDeleted cascades cleanup to other handlers
        if (event is SseEvent.SessionDeleted) {
            val deletedSessionId = event.info.id
            streamingSessionOwners.remove(deletedSessionId)
            messageHandler.clearForSession(deletedSessionId)
            permissionHandler.clearForSession(deletedSessionId)
            questionHandler.clearForSession(deletedSessionId)
            miscHandler.clearForSession(deletedSessionId)
            sessionNextHandler.clearForSession(deletedSessionId)
            sessionStateService.clearSession(deletedSessionId)
        }

        // Cross-handler: CommandExecuted — only mark messages as completed.
        // Don't force session to Idle: the server sends session.status event
        // if the session actually becomes idle. Forcing Idle here causes
        // flickering when the agent continues to the next tool call.
        if (event is SseEvent.CommandExecuted) {
            messageHandler.markSessionIdle(event.sessionId)
        }

        // Track user message times for stable session sort ordering.
        if (event is SseEvent.MessageUpdated && event.info is Message.User) {
            sessionHandler.recordUserMessage(event.info.sessionId, event.info.time.created)
        }
    }

    /**
     * Check if a session has any assistant messages still streaming (time.completed == null).
     * Used by REST sync logic and L5 cross-validation checker.
     */
    private fun hasIncompleteAssistant(sessionId: String): Boolean {
        return messageHandler.messages.value[sessionId]
            .orEmpty()
            .filterIsInstance<Message.Assistant>()
            .any { it.time.completed == null }
    }

    // ============ FSM Forwarding ============

    /**
     * Forward SSE event to [SessionStateService] (the single source of truth) for FSM processing.
     */
    private fun forwardToSessionStateService(event: SseEvent) {
        val fsmSessionId = extractSessionId(event)
        if (fsmSessionId != null) {
            sessionStateService.onSseEvent(event, fsmSessionId)
        }
    }

    /**
     * Extract sessionId from any [SseEvent] subclass.
     * Returns null for events that have no associated session.
     */
    private fun extractSessionId(event: SseEvent): String? {
        return when (event) {
            // Session lifecycle (status-relevant for FSM)
            is SseEvent.SessionStatus -> event.sessionId
            is SseEvent.SessionIdle -> event.sessionId
            is SseEvent.SessionError -> event.sessionId
            is SseEvent.SessionNext -> event.event.sessionId
            // Session lifecycle (info)
            is SseEvent.SessionCreated -> event.info.id
            is SseEvent.SessionUpdated -> event.info.id
            is SseEvent.SessionDeleted -> event.info.id
            is SseEvent.SessionDiff -> event.sessionId
            is SseEvent.SessionCompacted -> event.sessionId
            // Messages
            is SseEvent.MessageUpdated -> event.info.sessionId
            is SseEvent.MessageRemoved -> event.sessionId
            is SseEvent.MessagePartUpdated -> event.part.sessionId
            is SseEvent.MessagePartDelta -> event.sessionId
            is SseEvent.MessagePartRemoved -> event.sessionId
            // Permission / Question
            is SseEvent.PermissionAsked -> event.sessionId
            is SseEvent.PermissionReplied -> event.sessionId
            is SseEvent.QuestionAsked -> event.sessionId
            is SseEvent.QuestionReplied -> event.sessionId
            is SseEvent.QuestionRejected -> event.sessionId
            // Todo / Command
            is SseEvent.TodoUpdated -> event.sessionId
            is SseEvent.CommandExecuted -> event.sessionId
            // Events without sessionId
            is SseEvent.ServerConnected -> null
            is SseEvent.ServerHeartbeat -> null
            is SseEvent.ServerInstanceDisposed -> null
            is SseEvent.VcsBranchUpdated -> null
            is SseEvent.LspUpdated -> null
            is SseEvent.ProjectUpdated -> null
            is SseEvent.PtyCreated -> null
            is SseEvent.PtyUpdated -> null
            is SseEvent.PtyDeleted -> null
            is SseEvent.WorkspaceReady -> null
            is SseEvent.WorkspaceFailed -> null
            is SseEvent.FileEdited -> null
            is SseEvent.McpToolsChanged -> null
            is SseEvent.FileWatcherUpdated -> null
            is SseEvent.InstallationUpdated -> null
            is SseEvent.InstallationUpdateAvailable -> null
            is SseEvent.WorktreeReady -> null
            is SseEvent.WorktreeFailed -> null
        }
    }

    // ============ Delegated Operations ============

    fun setSessions(serverId: String, sessions: List<Session>) =
        sessionHandler.setSessions(serverId, sessions)

    fun clearRevert(sessionId: String) {
        // Prune reverted messages from cache BEFORE clearing the filter.
        // Otherwise the filter drops, reverted messages briefly reappear,
        // then the server's message.removed SSE catches up — visible flash.
        val revert = sessionHandler.sessions.value
            .find { it.id == sessionId }?.revert
        if (revert != null) {
            messageHandler.pruneRevertedMessages(sessionId, revert.messageId)
        }
        sessionHandler.clearRevert(sessionId)
    }

    fun setRevert(sessionId: String, messageId: String) =
        sessionHandler.setRevert(sessionId, messageId)

    fun setMessages(sessionId: String, messages: List<MessageWithParts>) =
        messageHandler.setMessages(sessionId, messages)

    fun mergeMessages(sessionId: String, messages: List<MessageWithParts>) =
        messageHandler.mergeMessages(sessionId, messages)

    fun replaceMessages(sessionId: String, messages: List<MessageWithParts>) =
        messageHandler.replaceMessages(sessionId, messages)

    fun addOptimisticMessage(sessionId: String, message: Message.User, parts: List<Part>) =
        messageHandler.addOptimisticMessage(sessionId, message, parts)

    fun removePermission(permissionId: String) =
        permissionHandler.removePermission(permissionId)

    fun setPermissions(sessionId: String, permissions: List<SseEvent.PermissionAsked>) =
        permissionHandler.setPermissions(sessionId, permissions)

    fun removeQuestion(questionId: String) =
        questionHandler.removeQuestion(questionId)

    fun setQuestions(sessionId: String, questions: List<SseEvent.QuestionAsked>) =
        questionHandler.setQuestions(sessionId, questions)

    fun trackSequence(sessionId: String, seq: Long) {
        sessionNextHandler.trackSequence(sessionId, seq)
    }

    fun clearGap(sessionId: String) {
        sessionNextHandler.clearGap(sessionId)
    }

    // ============ Child-session Aggregation ============

    /** Aggregate permissions for a session including its child sessions. */
    fun getPermissionsWithChildren(sessionId: String, sessions: List<Session>) =
        permissionHandler.getPermissionsWithChildren(sessionId, sessions)

    /** Aggregate questions for a session including its child sessions. */
    fun getQuestionsWithChildren(sessionId: String, sessions: List<Session>) =
        questionHandler.getQuestionsWithChildren(sessionId, sessions)

    fun clearAll() {
        sessionHandler.clearAll()
        messageHandler.clearAll()
        permissionHandler.clearAll()
        questionHandler.clearAll()
        miscHandler.clearAll()
        sessionNextHandler.clearAll()
        sessionStateService.clearAll()
        streamingSessionOwners.clear()
    }

    fun clearForServer(serverId: String) {
        val sessionIds = sessionHandler.serverSessions.value[serverId] ?: emptySet()
        sessionHandler.clearForServer(serverId)
        messageHandler.clearForServer(sessionIds)
        permissionHandler.clearForServer(sessionIds)
        questionHandler.clearForServer(sessionIds)
        miscHandler.clearForServer(sessionIds)
        sessionNextHandler.clearForServer(sessionIds)
        // Release streaming ownership for sessions owned by this server,
        // allowing another server to claim them if still connected.
        streamingSessionOwners.entries.removeAll { it.value == serverId }
    }
}

