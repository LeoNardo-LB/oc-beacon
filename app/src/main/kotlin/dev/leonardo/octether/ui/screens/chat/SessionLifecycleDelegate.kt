package dev.leonardo.octether.ui.screens.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import dev.leonardo.octether.BuildConfig
import dev.leonardo.octether.domain.repository.SessionRepository
import dev.leonardo.octether.domain.usecase.ManageSessionUseCase
import dev.leonardo.octether.ui.navigation.routes.ChatNav
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLDecoder

private const val TAG = "SessionLifecycleDelegate"

/**
 * Owns session identity, directory, lazy creation, and the session-loaded signal
 * previously inlined in [ChatViewModel] — the **spine** of the delegate layer.
 *
 * Extracted in Phase 3 Task 3 (C cluster).
 *
 * [sessionIdFlow] is the source for 6 `combine`/`flatMapLatest` pipelines in
 * [ChatViewModel] (messageListState, modelConfigState, sessionMetaState,
 * interactionState, directoryState, contextDetailState). Other delegates
 * (Terminal, DraftInput) consume [sessionDirectory] and [sessionLoaded] via
 * their constructor providers.
 *
 * NOTE: Intentionally NOT `@Singleton`/`@Inject`. It holds per-ChatViewModel
 * runtime context (SavedStateHandle route params, the ViewModel's coroutine
 * scope, and cross-cluster callbacks for message loading/observation) that
 * Hilt cannot supply. ChatViewModel constructs it directly and re-exposes
 * every member as a facade, so UI files are unchanged.
 *
 * Cross-cluster side effects (loading messages, starting SSE observation) are
 * injected as callbacks so [ensureSession]'s mutex still wraps the full
 * critical section, preserving the original single-execution semantics.
 */
internal class SessionLifecycleDelegate(
    private val manageSessionUseCase: ManageSessionUseCase,
    private val sessionRepository: SessionRepository,
    private val serverId: String,
    savedStateHandle: SavedStateHandle,
    private val scope: CoroutineScope,
    private val onMessagesNeedLoading: suspend () -> Unit,
    private val onStartObservingMessages: () -> Unit,
) {
    private val directoryParam: String = URLDecoder.decode(
        savedStateHandle.get<String>(ChatNav.PARAM_DIRECTORY) ?: "", "UTF-8"
    )
    private val _sessionId = MutableStateFlow(
        URLDecoder.decode(savedStateHandle.get<String>("sessionId") ?: "", "UTF-8")
    )

    /** Stable identity flow — the source for 6 combine/flatMapLatest pipelines. */
    val sessionIdFlow: StateFlow<String> = _sessionId

    /** Synchronous identity read. */
    val sessionId: String get() = _sessionId.value

    /**
     * The directory of this session's project — sent as x-opencode-directory
     * so the server resolves the correct project context. Many REST calls use
     * this as their `directory` parameter.
     */
    var sessionDirectory: String? = null
        private set

    /** Mutex to prevent concurrent session creation. */
    private val sessionCreateMutex = Mutex()

    /**
     * Signals when [loadSession] has finished (successfully or with error), so
     * that terminal creation can wait for [sessionDirectory] to be populated.
     */
    val sessionLoaded: CompletableDeferred<Unit> = CompletableDeferred()

    /**
     * Set up state for a brand-new session (no server session yet): apply the
     * route directory param and mark the session as loaded so waiters proceed.
     */
    fun initForNewSession() {
        if (directoryParam.isNotEmpty()) {
            sessionDirectory = directoryParam
        }
        if (!sessionLoaded.isCompleted) {
            sessionLoaded.complete(Unit)
        }
    }

    /**
     * Load session info via V1 API, then trigger cross-cluster message loading
     * and SSE observation through the injected callbacks.
     *
     * Safe to call only for an existing session (non-empty [sessionId]).
     */
    suspend fun loadSession() {
        try {
            // 1. Load session info for directory / session metadata
            val session = manageSessionUseCase.getSession(serverId, sessionId)
            if (session.directory.isNotBlank()) {
                sessionDirectory = session.directory
                if (BuildConfig.DEBUG) Log.d(TAG, "Session directory: ${session.directory}")
            }
            sessionRepository.setSessions(serverId, listOf(session))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load session info", e)
        } finally {
            if (!sessionLoaded.isCompleted) {
                sessionLoaded.complete(Unit)
            }
        }

        // 2. Cross-cluster: load messages via V1 API (currentMessageLimit + listMessages)
        runCatching { onMessagesNeedLoading() }
            .onFailure { Log.e(TAG, "Failed to load messages", it) }

        // 3. Cross-cluster: start observing chatRepository flows (driven by SSE EventDispatcher)
        runCatching { onStartObservingMessages() }
            .onFailure { Log.e(TAG, "Failed to start observing messages", it) }
    }

    /**
     * Ensures a session exists before sending messages.
     * If sessionId is empty (new session), creates one via API.
     * Thread-safe via Mutex to prevent duplicate creation.
     * After creation, starts observing SSE-driven flows so messages appear.
     */
    suspend fun ensureSession(): String {
        if (sessionId.isNotEmpty()) return sessionId
        return sessionCreateMutex.withLock {
            // Double-check after acquiring lock
            if (sessionId.isNotEmpty()) return sessionId
            val dir = if (directoryParam.isNotEmpty()) directoryParam else sessionDirectory
            val session = manageSessionUseCase.createSession(serverId, directory = dir)
            sessionRepository.setSessions(serverId, listOf(session))
            _sessionId.value = session.id
            sessionDirectory = session.directory.ifBlank { dir }
            if (!sessionLoaded.isCompleted) {
                sessionLoaded.complete(Unit)
            }
            // Start observing SSE-driven message/part flows for the new session.
            // Without this, SSE events arrive at EventDispatcher but ChatViewModel
            // never collects them — messages stay invisible.
            runCatching { onStartObservingMessages() }
                .onFailure { Log.e(TAG, "Failed to start observing after session creation", it) }
            sessionId
        }
    }
}
