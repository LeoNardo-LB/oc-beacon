package dev.leonardo.ocremoteplus.service

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import dev.leonardo.ocremoteplus.BuildConfig
import dev.leonardo.ocremoteplus.data.api.NetworkMonitor
import dev.leonardo.ocremoteplus.data.api.file.FileApi
import dev.leonardo.ocremoteplus.data.api.message.MessageApi
import dev.leonardo.ocremoteplus.data.api.session.SessionApi
import dev.leonardo.ocremoteplus.domain.model.ServerConnection
import dev.leonardo.ocremoteplus.data.api.SseClient
import dev.leonardo.ocremoteplus.data.api.SseReadTimeoutTracker
import dev.leonardo.ocremoteplus.data.repository.EventDispatcher
import dev.leonardo.ocremoteplus.data.repository.SessionStateService
import dev.leonardo.ocremoteplus.domain.model.Project
import dev.leonardo.ocremoteplus.domain.model.ServerConfig
import dev.leonardo.ocremoteplus.domain.model.SseEvent
import dev.leonardo.ocremoteplus.data.repository.SettingsDataStore
import dev.leonardo.ocremoteplus.logging.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SseConnManager"
private const val RECONNECT_BASE_DELAY_MS = 1_000L
private const val RECONNECT_MAX_DELAY_MS = 30_000L
private const val RECONNECT_BACKOFF_FACTOR = 2.0
private const val COOLDOWN_CHECK_INTERVAL_MS = 30_000L

/**
 * Per-server connection state.
 */
data class ServerConnectionState(
    val config: ServerConfig,
    val conn: ServerConnection,
    val sseJob: Job,
    val isConnected: Boolean = false,
    val onEvent: (ServerConfig, SseEvent) -> Unit = { _, _ -> }
)

/**
 * Manages SSE connections to multiple servers.
 * Handles connection lifecycle, auto-reconnect, session pre-loading,
 * and event dispatching via [EventDispatcher].
 *
 * Event routing (e.g. for notifications) is delegated to the caller via [onEvent] callback.
 */
@Singleton
class SseConnectionManager @Inject constructor(
    private val sessionApi: SessionApi,
    private val messageApi: MessageApi,
    private val fileApi: FileApi,
    private val sseClient: SseClient,
    private val eventDispatcher: EventDispatcher,
    private val settingsRepository: SettingsDataStore,
    private val networkMonitor: NetworkMonitor,
    private val sessionStateService: SessionStateService,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, exception ->
            // Defense-in-depth: ensures uncaught exceptions during SSE connection
            // lifecycle (DNS failures, reconnect races) do not crash the process.
            // The launch in startSseConnection wraps network calls in try/catch,
            // but this catches anything that escapes — particularly important because
            // reconnectServer() cancels the running job then starts a new one, which can
            // race with an in-flight DNS resolution and surface as UnknownHostException.
            Log.e(TAG, "Unhandled coroutine exception in SSE connection scope", exception)
        }
    )

    /** All active/pending server connections keyed by serverId. */
    val connections = ConcurrentHashMap<String, ServerConnectionState>()

    /** Per-server timeout trackers for SSE read timeout cooldown logic. */
    private val timeoutTrackers = ConcurrentHashMap<String, SseReadTimeoutTracker>()

    /**
     * RS-017 fix: Per-server reconnect guard. Prevents overlapping reconnect
     * attempts when the network state bounces (Available → Lost → Available)
     * and debounce + distinctUntilChanged don't fully deduplicate.
     */
    private val reconnectingServers = ConcurrentHashMap.newKeySet<String>()

    /** Observable set of server IDs that are actually connected (SSE stream active). */
    val connectedServerIds: StateFlow<Set<String>>
        get() = _connectedServerIds.asStateFlow()
    private val _connectedServerIds = MutableStateFlow<Set<String>>(emptySet())

    /** Observable set of server IDs that are attempting to connect. */
    val connectingServerIds: StateFlow<Set<String>>
        get() = _connectingServerIds.asStateFlow()
    private val _connectingServerIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Start an SSE connection to [server].
     *
     * @param server  The server configuration to connect to.
     * @param onEvent  Callback invoked for each SSE event (for notification routing, etc.).
     * @return The [Job] representing the connection coroutine.
     */
    fun startConnection(
        server: ServerConfig,
        onEvent: (ServerConfig, SseEvent) -> Unit
    ): Job {
        // RS-004 fix: self-protection against duplicate calls. If an existing
        // connection is present, cancel it before starting a new one. The caller
        // (OpenCodeConnectionService.connect) also checks, but this prevents
        // leaks if startConnection is called directly (tests, future refactors).
        connections[server.id]?.sseJob?.cancel()

        val conn = ServerConnection.from(server.url, server.username, server.password)
        val job = startSseConnection(server, conn, onEvent)

        connections[server.id] = ServerConnectionState(
            config = server, conn = conn, sseJob = job, isConnected = false, onEvent = onEvent
        )
        _connectingServerIds.update { it + server.id }

        return job
    }

    /**
     * Stop the SSE connection to a specific server.
     */
    fun stopConnection(serverId: String) {
        val state = connections.remove(serverId) ?: return
        state.sseJob.cancel()
        timeoutTrackers.remove(serverId)
        _connectedServerIds.update { it - serverId }
        _connectingServerIds.update { it - serverId }
        eventDispatcher.clearForServer(serverId)
    }

    /**
     * Stop all SSE connections.
     */
    fun stopAllConnections() {
        // RS-017 fix: block reconnect attempts while we're tearing down
        val serverIds = connections.keys.toList()
        for ((_, state) in connections) {
            state.sseJob.cancel()
        }
        connections.clear()
        timeoutTrackers.clear()
        // RS-002 fix: use .update{} instead of direct assignment to participate
        // in CAS, preventing a cancelled-but-still-running SSE coroutine's
        // updateServerConnected call from resurrecting a cleared server ID.
        _connectedServerIds.update { emptySet() }
        _connectingServerIds.update { emptySet() }
        for (serverId in serverIds) {
            eventDispatcher.clearForServer(serverId)
        }
    }

    /**
     * Reconnect all active connections. Used when network recovers from a loss.
     * Restarts the SSE connection for each server so the auto-reconnect loop
     * resets its attempt counter and reconnects immediately.
     *
     * RS-001 fix: now suspend — each reconnectServer uses cancelAndJoin() to
     * ensure the old SSE coroutine fully stops before starting a new one.
     */
    suspend fun reconnectAll() {
        for ((serverId, _) in connections.toMap()) {
            reconnectServer(serverId)
        }
    }

    /**
     * Reconnect a single server connection. Restarts the SSE connection so
     * the auto-reconnect loop resets its attempt counter and reconnects immediately.
     *
     * RS-001 fix: uses cancelAndJoin() instead of bare cancel() to ensure the
     * old coroutine fully completes (including preLoadSessions/recoverMessages)
     * before starting a new one. Prevents overlapping eventDispatcher mutations.
     *
     * RS-017 fix: per-server reconnect guard prevents overlapping reconnect
     * attempts when network state bounces rapidly.
     */
    private suspend fun reconnectServer(serverId: String) {
        // RS-017: skip if a reconnect is already in progress for this server
        if (!reconnectingServers.add(serverId)) {
            Log.w(TAG, "Reconnect already in progress for $serverId, skipping")
            return
        }
        try {
            val state = connections[serverId] ?: return
            Log.i(TAG, "Reconnecting server $serverId after network recovery")
            timeoutTrackers[serverId]?.reset()
            // RS-001: wait for old coroutine to fully stop before starting new one
            state.sseJob.cancelAndJoin()
            val newJob = startSseConnection(state.config, state.conn, state.onEvent)
            // RS-003 fix: use computeIfPresent for atomic update — if the server
            // was removed during cancelAndJoin, don't resurrect it
            connections.computeIfPresent(serverId) { _, current ->
                current.copy(sseJob = newJob)
            }
        } finally {
            reconnectingServers.remove(serverId)
        }
    }

    /**
     * Check if a specific server is connected.
     */
    fun isConnected(serverId: String): Boolean {
        return connections[serverId]?.sseJob?.isActive == true
    }

    /**
     * Get the [ServerConnection] for a given server, if any.
     */
    fun getConnection(serverId: String): ServerConnection? {
        return connections[serverId]?.conn
    }

    /**
     * Cancel the internal coroutine scope. Call during service teardown.
     */
    fun cancelScope() {
        scope.cancel()
    }

    // ============ SSE Connection with Auto-Reconnect ============

    private fun startSseConnection(
        server: ServerConfig,
        conn: ServerConnection,
        onEvent: (ServerConfig, SseEvent) -> Unit
    ): Job {
        return scope.launch {
            var attempt = 0
            val tracker = timeoutTrackers.getOrPut(server.id) { SseReadTimeoutTracker() }

            while (isActive) {
                attempt++

                // If in cooldown, wait and skip reconnection attempt
                if (tracker.isInCooldown()) {
                    Log.i(TAG, "[${server.displayName}] SSE in cooldown, waiting ${COOLDOWN_CHECK_INTERVAL_MS}ms")
                    delay(COOLDOWN_CHECK_INTERVAL_MS)
                    continue
                }

                Log.i(TAG, "[${server.displayName}] SSE connection attempt #$attempt")

                // Pre-load sessions via REST API for all projects
                preLoadSessions(server, conn)

                // On reconnect (not first connection), recover messages missed during disconnection
                if (attempt > 1) {
                    recoverMessages(server, conn)
                }

                try {
                    sseClient.connectToGlobalEvents(conn)
                        .catch { error ->
                            Log.e(TAG, "[${server.displayName}] SSE stream error", error)
                            updateServerConnected(server.id, false)
                            tracker.recordTimeout()
                            throw error
                        }
                        .collect { event ->
                            // RS-005 fix: guard against events arriving after disconnect.
                            // stopConnection removes the entry from connections; if we
                            // see null here, the server was disconnected and we should
                            // skip event dispatch to avoid orphan events in EventDispatcher.
                            val currentState = connections[server.id]
                            if (currentState == null) return@collect
                            if (!currentState.isConnected) {
                                updateServerConnected(server.id, true)
                                attempt = 0
                            }
                            tracker.recordSuccess()
                            // Dispatch to EventDispatcher for state updates
                            eventDispatcher.processEvent(event, server.id)
                            // Route to caller for notification handling
                            onEvent(server, event)
                        }

                    // Flow completed normally (server closed connection)
                    Log.w(TAG, "[${server.displayName}] SSE stream completed")
                    updateServerConnected(server.id, false)
                    if (tracker.shouldEnterCooldown()) {
                        tracker.enterCooldown()
                        Log.w(TAG, "[${server.displayName}] Entering SSE cooldown after ${tracker.consecutiveTimeouts} consecutive timeouts")
                    } else {
                        tracker.recordTimeout()
                    }
                } catch (e: CancellationException) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "[${server.displayName}] SSE job cancelled, not reconnecting")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "[${server.displayName}] SSE connection failed: ${e.message}")
                    updateServerConnected(server.id, false)
                    if (tracker.shouldEnterCooldown()) {
                        tracker.enterCooldown()
                        Log.w(TAG, "[${server.displayName}] Entering SSE cooldown after ${tracker.consecutiveTimeouts} consecutive timeouts")
                    } else {
                        tracker.recordTimeout()
                    }
                }

                // If this server was removed from connections, stop the loop
                if (!connections.containsKey(server.id)) break

                val delayMs = calculateBackoff(attempt)
                Log.i(TAG, "[${server.displayName}] Reconnecting in ${delayMs}ms (attempt #$attempt)")
                delay(delayMs)
            }
        }
    }

    private suspend fun preLoadSessions(server: ServerConfig, conn: ServerConnection) {
        try {
            val projects = fileApi.listProjects(conn)
            if (projects.isEmpty()) {
                // Fallback: load sessions without directory header (server CWD only)
                val sessions = sessionApi.listSessions(conn)
                eventDispatcher.setSessions(server.id, sessions)
                Log.i(TAG, "[${server.displayName}] Pre-loaded ${sessions.size} sessions (no projects)")
            } else {
                var totalSessions = 0
                for (project in projects) {
                    try {
                        val sessions = sessionApi.listSessions(conn, directory = project.worktree)
                        eventDispatcher.setSessions(server.id, sessions)
                        totalSessions += sessions.size
                    } catch (e: Exception) {
                        Log.w(TAG, "[${server.displayName}] Failed to pre-load sessions for project ${project.displayName}: ${e.message}")
                    }
                }
                Log.i(TAG, "[${server.displayName}] Pre-loaded $totalSessions sessions across ${projects.size} projects")
            }
            // Initialize session statuses from server via the unified FSM pipeline
            // (aggregate across project worktrees + absence=idle + incomplete-protection).
            sessionStateService.setServerId(server.id)
            sessionStateService.syncFromRest(projects)
        } catch (e: Exception) {
            Log.w(TAG, "[${server.displayName}] Failed to pre-load sessions: ${e.message}")
        }
    }

    /**
     * Recover messages for all active sessions of a server after SSE reconnection.
     * Phase 1: Replace messages with REST data (source of truth).
     * Phase 2: Sync session statuses from server — only mark idle sessions as idle.
     */
    private suspend fun recoverMessages(server: ServerConfig, conn: ServerConnection) {
        val sessionIds = eventDispatcher.serverSessions.value[server.id] ?: return
        if (sessionIds.isEmpty()) return

        // Phase 1: Recover messages (REST as source of truth)
        Log.i(TAG, "[${server.displayName}] Recovering messages for ${sessionIds.size} sessions")
        var recoveredCount = 0
        for (sessionId in sessionIds) {
            try {
                val messages = messageApi.listMessages(conn, sessionId)
                eventDispatcher.replaceMessages(sessionId, messages)
                recoveredCount++
            } catch (e: Exception) {
                Log.w(TAG, "[${server.displayName}] Failed to recover messages for session $sessionId: ${e.message}")
            }
        }
        Log.i(TAG, "[${server.displayName}] Recovered messages for $recoveredCount/${sessionIds.size} sessions")

        // Phase 2: Sync real session statuses from server via the unified FSM pipeline.
        // Wrap in try-catch: listProjects / syncFromRest may throw NoTransformationFoundException
        // when the server returns a non-JSON error response (e.g. 502 Bad Gateway from a reverse
        // proxy). Phase 1 message recovery is already complete at this point, so a Phase 2 failure
        // should not propagate and abort the reconnect cycle.
        try {
            val projects = fileApi.listProjects(conn)
            sessionStateService.setServerId(server.id)
            sessionStateService.syncFromRest(projects)
        } catch (e: Exception) {
            Log.w(TAG, "[${server.displayName}] Failed to sync session statuses during recovery: ${e.message}")
        }
    }

    private fun updateServerConnected(serverId: String, connected: Boolean) {
        // RS-003 fix: use computeIfPresent for atomic read-modify-write.
        // The old pattern (read state, then replace) had a TOCTOU window where
        // reconnectServer could replace the state with a new sseJob between
        // the read and the write, causing the old state (with old sseJob)
        // to overwrite the new state.
        var found = false
        connections.computeIfPresent(serverId) { _, state ->
            found = true
            state.copy(isConnected = connected)
        }
        if (!found) return
        if (connected) {
            _connectingServerIds.update { it - serverId }
            _connectedServerIds.update { it + serverId }
            AppLogger.i(TAG, "Connected to server $serverId")
        } else {
            _connectedServerIds.update { it - serverId }
            _connectingServerIds.update { it + serverId }
            AppLogger.w(TAG, "Disconnected from server $serverId")
        }
    }

    private suspend fun calculateBackoff(attempt: Int): Long {
        val maxDelay = when (settingsRepository.reconnectMode.first()) {
            "aggressive" -> 5_000L
            "conservative" -> 60_000L
            else -> RECONNECT_MAX_DELAY_MS // normal: 30s
        }
        val delay = (RECONNECT_BASE_DELAY_MS * Math.pow(RECONNECT_BACKOFF_FACTOR, (attempt - 1).coerceAtLeast(0).toDouble())).toLong()
        return delay.coerceAtMost(maxDelay)
    }
}

