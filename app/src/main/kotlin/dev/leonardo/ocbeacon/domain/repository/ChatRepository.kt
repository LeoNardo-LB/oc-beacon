package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.AutoApproveRule
import dev.leonardo.ocbeacon.domain.model.CompactionStateInfo
import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.ModelSelection
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.PermissionState
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.model.QuestionState
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.StepProgressInfo
import dev.leonardo.ocbeacon.domain.model.ToolProgressInfo
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for chat operations.
 * Aligned with spec §4.1.1.
 * Implemented by the Data layer in Phase 3.
 */
interface ChatRepository {

    // ============ State Observations ============

    /**
     * Observe the list of messages (with parts) for a session.
     * Phase 3 impl: delegates to EventDispatcher.messages, maps to domain Message.
     */
    fun getMessagesFlow(sessionId: String): Flow<List<Message>>

    /**
     * Observe the list of parts for a session.
     */
    fun getParts(sessionId: String): Flow<List<Part>>

    /**
     * Observe the parts map (sessionId → parts) for all sessions.
     * Needed by combines that build per-message ChatMessage objects.
     */
    fun getAllPartsMap(): Flow<Map<String, List<Part>>>

    /**
     * Observe the list of pending permission requests for a session.
     */
    fun getPermissionsFlow(sessionId: String): Flow<List<PermissionState>>

    /**
     * Observe the list of pending questions for a session.
     */
    fun getQuestionsFlow(sessionId: String): Flow<List<QuestionState>>

    /**
     * Observe the raw questions map (sessionId → list) for all sessions.
     * Used by combines that need to reactively recompute when questions change.
     */
    fun getAllQuestionsFlow(): Flow<Map<String, List<SseEvent.QuestionAsked>>>

    /**
     * Observe the raw permissions map (sessionId → list) for all sessions.
     * Used by combines that need to reactively recompute when permissions change.
     */
    fun getAllPermissionsFlow(): Flow<Map<String, List<SseEvent.PermissionAsked>>>

    // ============ EventDispatcher Flow Exposure ============

    /**
     * Observe active tool progress for a server.
     */
    fun getActiveToolProgress(serverId: String): Flow<List<ToolProgressInfo>?>

    /**
     * Observe step progress for a server.
     */
    fun getStepProgress(serverId: String): Flow<StepProgressInfo?>

    /**
     * Observe compaction state for a server.
     */
    fun getCompactionState(serverId: String): Flow<CompactionStateInfo?>

    // ============ Network Operations ============

    /**
     * Send a message (list of parts) to the given session.
     * Returns the resulting [Message] on success, or an exception on failure.
     */
    suspend fun sendMessage(sessionId: String, parts: List<Part>): Result<Message>

    /**
     * Reply to a permission request by ID.
     */
    suspend fun replyPermission(permissionId: String, reply: String): Result<Boolean>

    /**
     * Reply to a question by ID.
     */
    suspend fun replyQuestion(questionId: String, answer: String): Result<Boolean>

    /**
     * Send a prompt asynchronously (fire-and-forget).
     */
    suspend fun promptAsync(
        serverId: String,
        sessionId: String,
        parts: List<PromptPart>,
        model: ModelSelection? = null,
        agent: String? = null,
        variant: String? = null,
        directory: String? = null
    ): Result<Unit>

    /**
     * Revert (undo) messages starting from the given messageId.
     */
    suspend fun revertSession(serverId: String, sessionId: String, messageId: String): Result<Unit>

    /**
     * Unrevert (redo) the last reverted message in a session.
     */
    suspend fun unrevertSession(serverId: String, sessionId: String): Result<Unit>

    /**
     * Respond to a permission request with server context.
     */
    suspend fun respondPermission(
        serverId: String,
        permissionId: String,
        reply: String,
        directory: String? = null
    ): Result<Boolean>

    /**
     * Select a model for the server.
     */
    suspend fun selectModel(serverId: String, providerId: String, modelId: String): Result<Unit>

    // ============ Pending Queries ============

    /**
     * List pending permission requests for a server.
     */
    suspend fun listPendingPermissions(serverId: String, directory: String? = null): Result<List<PermissionState>>

    /**
     * List pending question requests for a server.
     */
    suspend fun listPendingQuestions(serverId: String, directory: String? = null): Result<List<QuestionState>>

    /**
     * Reply to a question request with multiple answers.
     */
    suspend fun replyToQuestion(
        serverId: String,
        requestId: String,
        answers: List<List<String>>,
        directory: String? = null
    ): Result<Boolean>

    /**
     * Reject a question request.
     */
    suspend fun rejectQuestion(
        serverId: String,
        requestId: String,
        directory: String? = null
    ): Result<Boolean>

    // ============ Undo/Redo ============

    /**
     * Undo or redo messages in a session.
     * @param action "undo" or "redo"
     */
    suspend fun undoRedo(serverId: String, sessionId: String, action: String): Result<Unit>

    // ============ Command Execution ============

    /**
     * Execute a server-side command in a session.
     */
    suspend fun executeCommand(
        serverId: String,
        sessionId: String,
        command: String,
        arguments: String = "",
        directory: String? = null
    ): Result<Boolean>

    /**
     * Run a shell command in a session.
     */
    suspend fun runShellCommand(
        serverId: String,
        sessionId: String,
        command: String,
        agent: String,
        providerId: String? = null,
        modelId: String? = null,
        directory: String? = null
    ): Result<Boolean>

    // ============ UI State ============

    /**
     * Get the read-only map of tool expanded states for the current session.
     * Used by UI to track which tool cards are expanded.
     */
    fun getToolExpandedStates(): Map<String, Boolean>

    /**
     * Set the expanded state for a specific tool card.
     */
    fun setToolExpanded(toolId: String, expanded: Boolean)

    // ============ Permission Auto-Approve ============

    /**
     * Persist a new permission auto-approve rule (user chose "always approve").
     */
    suspend fun addPermissionAutoApproveRule(rule: AutoApproveRule)

    // ============ Write Operations (State Updates) ============

    /**
     * Set messages for a session (full replace from REST load).
     */
    fun setMessages(sessionId: String, messages: List<MessageWithParts>)

    /**
     * Merge messages into a session (REST restore / pagination).
     */
    fun mergeMessages(sessionId: String, messages: List<MessageWithParts>)

    /**
     * Replace all messages for a session (session update refresh).
     */
    fun replaceMessages(sessionId: String, messages: List<MessageWithParts>)

    /**
     * Inject an optimistic user message into the message cache for immediate display.
     * The message uses a temp ID ("pending-*") that gets replaced in-place when
     * SSE delivers the real message.
     */
    fun addOptimisticMessage(sessionId: String, message: Message.User, parts: List<Part>)

    /**
     * Clear the revert state for a session.
     * Called when the user sends a new message after revert — the server
     * consumes the revert but may not notify the client via SSE.
     */
    fun clearRevert(sessionId: String)

    /** Set local revert state immediately after REST revert (prevents flash of old messages). */
    fun setRevert(sessionId: String, messageId: String)

    /**
     * Remove a permission card by ID (optimistic removal after reply).
     */
    fun removePermission(permissionId: String)

    /**
     * Set permissions for a session (REST merge).
     */
    fun setPermissions(sessionId: String, permissions: List<SseEvent.PermissionAsked>)

    /**
     * Remove a question card by ID (optimistic removal after reply).
     */
    fun removeQuestion(questionId: String)

    /**
     * Set questions for a session (REST merge).
     */
    fun setQuestions(sessionId: String, questions: List<SseEvent.QuestionAsked>)

    /**
     * Aggregate permissions for a session including its child sessions.
     */
    fun getPermissionsWithChildren(sessionId: String, sessions: List<Session>): List<SseEvent.PermissionAsked>

    /**
     * Aggregate questions for a session including its child sessions.
     */
    fun getQuestionsWithChildren(sessionId: String, sessions: List<Session>): List<SseEvent.QuestionAsked>

    // ============ Raw State Reads (for complex read-write patterns) ============

    /**
     * Read the current permissions map snapshot.
     * Needed for REST merge logic that reads existing SSE state before merging.
     */
    fun getPermissionsSnapshot(): Map<String, List<SseEvent.PermissionAsked>>

    /**
     * Read the current questions map snapshot.
     * Needed for REST merge logic that reads existing SSE state before merging.
     */
    fun getQuestionsSnapshot(): Map<String, List<SseEvent.QuestionAsked>>

    /**
     * Read the current sessions list snapshot.
     * Needed for REST merge logic that looks up child sessions and titles.
     */
    fun getSessionsSnapshot(): List<Session>

    /**
     * Observe active tool progress for a specific session (keyed by sessionId).
     */
    fun getActiveToolProgressForSession(sessionId: String): Flow<List<ToolProgressInfo>?>

    /**
     * Observe step progress for a specific session (keyed by sessionId).
     */
    fun getStepProgressForSession(sessionId: String): Flow<StepProgressInfo?>

    /**
     * Observe compaction state for a specific session (keyed by sessionId).
     */
    fun getCompactionStateForSession(sessionId: String): Flow<CompactionStateInfo?>

    /**
     * Observe file diffs for a specific session (keyed by sessionId).
     * Backs [dev.leonardo.ocbeacon.domain.model.Part.Patch] line counts.
     */
    fun getSessionDiffsForSession(sessionId: String): Flow<List<FileDiff>>
}
