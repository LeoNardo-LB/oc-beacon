package dev.leonardo.octether.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import dev.leonardo.octether.BuildConfig
import dev.leonardo.octether.MainActivity
import dev.leonardo.octether.R
import dev.leonardo.octether.data.api.NetworkMonitor
import dev.leonardo.octether.data.api.NetworkState
import dev.leonardo.octether.data.repository.EventDispatcher
import dev.leonardo.octether.data.repository.LocalServerManager
import dev.leonardo.octether.data.repository.ServerDataStore
import dev.leonardo.octether.data.repository.SettingsDataStore
import dev.leonardo.octether.domain.model.ServerConfig
import dev.leonardo.octether.domain.model.SseEvent
import dev.leonardo.octether.domain.repository.SettingsRepository as DomainSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject

private const val TAG = "OpenCodeService"
private const val WAKELOCK_TAG = "OpenCodeRemote::SSEConnection"

/**
 * Foreground Service for maintaining OpenCode SSE connections to multiple servers.
 *
 * This service:
 * - Maintains persistent SSE connections to one or more servers simultaneously
 * - Delegates connection lifecycle to [SseConnectionManager]
 * - Delegates notification management to [AppNotificationManager]
 * - Shows notifications for task completion and permission requests
 * - Holds a single partial WakeLock while any server is connected
 *
 * The connections stay alive until the user explicitly disconnects each server
 * (or uses "Disconnect All").
 */
@AndroidEntryPoint
class OpenCodeConnectionService : Service() {

    override fun attachBaseContext(newBase: Context) {
        val languageCode = SettingsDataStore.getStoredLanguage(newBase)
        if (languageCode.isNotEmpty()) {
            val locale = MainActivity.parseLocale(languageCode)
            Locale.setDefault(locale)
            val config = newBase.resources.configuration
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    @Inject
    lateinit var connectionManager: SseConnectionManager

    @Inject
    lateinit var appNotificationManager: AppNotificationManager

    @Inject
    lateinit var eventDispatcher: EventDispatcher

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    @Inject
    lateinit var serverDataStore: ServerDataStore

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    @Inject
    lateinit var sessionFocusHolder: SessionFocusHolder

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, exception ->
            // Defense-in-depth: ensures uncaught exceptions (e.g. UnknownHostException
            // during auto-connect to unreachable servers like Tailscale nodes) do not
            // propagate to the thread UncaughtExceptionHandler and crash the process.
            // Individual launches already wrap network calls in try/catch; this catches
            // anything that escapes (race conditions during reconnect, R8 inlining, etc.).
            Log.e(TAG, "Unhandled coroutine exception in serviceScope", exception)
        }
    )

    private var notificationWatchdogJob: Job? = null
    private var networkRecoveryJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var systemNotificationManager: NotificationManager
    private var foregroundStarted: Boolean = false

    /** Observable set of server IDs that are actually connected (SSE stream active). */
    val connectedServerIds get() = connectionManager.connectedServerIds

    /** Observable set of server IDs that are attempting to connect. */
    val connectingServerIds get() = connectionManager.connectingServerIds

    inner class LocalBinder : Binder() {
        fun getService(): OpenCodeConnectionService = this@OpenCodeConnectionService
    }

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Log.d(TAG, "Service created")

        systemNotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        appNotificationManager.createNotificationChannels(systemNotificationManager, this)

        // Start network monitoring and observe recovery events
        networkMonitor.startMonitoring()
        networkRecoveryJob = serviceScope.launch {
            networkMonitor.networkState
                .debounce(2_000L)
                .distinctUntilChanged()
                .collect { state ->
                    if (state == NetworkState.Available && connectionManager.connections.isNotEmpty()) {
                        Log.i(TAG, "Network recovered, reconnecting ${connectionManager.connections.size} server(s)")
                        connectionManager.reconnectAll()
                    }
                }
        }

        serviceScope.launch {
            autoConnectConfiguredServers()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BuildConfig.DEBUG) Log.d(TAG, "Service started, action=${intent?.action}")

        when (intent?.action) {
            ACTION_DISCONNECT_ALL -> {
                Log.i(TAG, "Disconnect All requested via notification")
                disconnectAllVisibleServers()
                return START_NOT_STICKY
            }
            ACTION_DISCONNECT -> {
                val serverId = intent.getStringExtra("server_id")
                if (serverId != null) {
                    Log.i(TAG, "Disconnect requested for server $serverId")
                    disconnect(serverId)
                }
                return START_NOT_STICKY
            }
        }

        ensureForegroundStarted()

        // Read server details from intent and connect
        intent?.let { i ->
            val serverId = i.getStringExtra("server_id")
            val serverName = i.getStringExtra("server_name")
            val serverUrl = i.getStringExtra("server_url")
            val serverUsername = i.getStringExtra("server_username") ?: "opencode"
            val serverPassword = i.getStringExtra("server_password")

            if (serverId != null && serverUrl != null) {
                val serverConfig = ServerConfig(
                    id = serverId,
                    url = serverUrl,
                    username = serverUsername,
                    password = serverPassword,
                    name = serverName
                )
                connect(serverConfig)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.DEBUG) Log.d(TAG, "Service destroyed")
        // RS-018 fix: cancel networkRecoveryJob and wait for it to complete
        // before stopping connections. If the recovery job is in the middle of
        // reconnectAll(), it might start new SSE jobs after stopAllConnections()
        // runs. By launching the cleanup in serviceScope (which is cancelled last),
        // we ensure ordering: recovery job stops → connections stop → scope cancels.
        networkRecoveryJob?.cancel()
        networkRecoveryJob = null
        networkMonitor.stopMonitoring()
        connectionManager.stopAllConnections()
        serviceScope.cancel()
    }

    // ============ Public API ============

    /**
     * Connect to an OpenCode server. If already connected to this server, no-op.
     * Multiple servers can be connected simultaneously — but not to the same
     * backend (same url + username). Duplicate backend connections would
     * deliver identical SSE events twice, causing doubled streaming output.
     */
    fun connect(server: ServerConfig) {
        if (connectionManager.connections.containsKey(server.id)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Already connected to server ${server.id}, skipping")
            return
        }

        // Dedup by backend signature: same url + username = same OpenCode serve instance.
        // Two SSE connections to the same backend deliver duplicate global events,
        // causing MessagePartDelta (append semantics) to double the streaming text.
        val existingBackend = connectionManager.connections.values.firstOrNull { state ->
            state.config.url == server.url && state.config.username == server.username
        }
        if (existingBackend != null) {
            Log.w(TAG, "Backend ${server.url} already connected via '${existingBackend.config.displayName}'" +
                " (id=${existingBackend.config.id}), skipping duplicate for '${server.displayName}'")
            return
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "Connecting to server: ${server.displayName} (${server.url})")

        ensureForegroundStarted()

        // Acquire wake lock (shared — first connect acquires, last disconnect releases)
        acquireWakeLock()

        // Start SSE connection with auto-reconnect; events routed to processEvent
        connectionManager.startConnection(server, ::processEvent)

        // Update persistent notification
        updatePersistentNotification()

        // Start watchdog if not already running
        startNotificationWatchdog()
    }

    /**
     * Disconnect from a single server.
     */
    fun disconnect(serverId: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Disconnecting server $serverId")

        connectionManager.stopConnection(serverId)

        if (connectionManager.connections.isEmpty()) {
            // Last server disconnected — clean up and stop service
            releaseWakeLock()
            notificationWatchdogJob?.cancel()
            notificationWatchdogJob = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
        } else {
            updatePersistentNotification()
        }
    }

    /**
     * Disconnect from all servers and stop the service.
     */
    fun disconnectAll() {
        disconnectAllInternal(stopService = true)
    }

    /**
     * Check if a specific server is connected.
     */
    fun isConnected(serverId: String): Boolean {
        return connectionManager.isConnected(serverId)
    }

    // ============ Internal ============

    private fun disconnectAllVisibleServers() {
        val visibleServerIds = connectionManager.connections.values
            .filterNot { isLocalServer(it.config) }
            .map { it.config.id }

        if (visibleServerIds.isEmpty()) {
            updatePersistentNotification()
            return
        }

        for (serverId in visibleServerIds) {
            disconnect(serverId)
        }
    }

    private fun disconnectAllInternal(stopService: Boolean) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Disconnecting all servers")

        connectionManager.stopAllConnections()

        releaseWakeLock()
        notificationWatchdogJob?.cancel()
        notificationWatchdogJob = null

        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
        }
    }

    private suspend fun autoConnectConfiguredServers() {
        try {
            val autoConnectServers = serverDataStore.servers.first().filter { it.autoConnect }
            if (autoConnectServers.isEmpty()) return
            Log.i(TAG, "Auto-connecting ${autoConnectServers.size} server(s)")
            autoConnectServers.forEach { connect(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-connect servers", e)
        }
    }

    private fun ensureForegroundStarted() {
        if (foregroundStarted) return
        val notification = appNotificationManager.createPersistentNotification(
            this, connectionManager.connections, ::isLocalServer
        )
        startForeground(AppNotificationManager.PERSISTENT_NOTIFICATION_ID, notification)
        foregroundStarted = true
    }

    // ============ Event Processing (notification routing only) ============

    private fun processEvent(server: ServerConfig, event: SseEvent) {
        if (BuildConfig.DEBUG) Log.d(TAG, "[${server.displayName}] SSE event: ${event.javaClass.simpleName}")

        // EventDispatcher.processEvent is already called by SseConnectionManager
        // Here we only route to notification logic
        when (event) {
            is SseEvent.SessionIdle -> {
                // Suppress if user is actively viewing this session
                if (sessionFocusHolder.shouldSuppress(server.id, event.sessionId)) return
                if (appNotificationManager.isChildSession(event.sessionId)) return
                serviceScope.launch {
                    if (!settingsDataStore.notificationsEnabled.first()) return@launch

                    // Give reducer a brief moment to receive trailing message/part events.
                    delay(250)

                    val assistantMessageId = appNotificationManager.checkNewAssistantMessage(event.sessionId)
                    if (assistantMessageId == null) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "[${server.displayName}] Skip response-ready: no assistant text output (${event.sessionId})")
                        }
                        return@launch
                    }

                    Log.i(TAG, "[${server.displayName}] Session idle -> Response ready for ${event.sessionId}")
                    appNotificationManager.showTaskCompleteNotification(
                        this@OpenCodeConnectionService, systemNotificationManager, server, event.sessionId
                    )
                }
            }
            is SseEvent.PermissionAsked -> {
                val targetSessionId = if (appNotificationManager.isChildSession(event.sessionId)) {
                    // 子 session 权限冒泡到父 session 通知
                    val session = eventDispatcher.sessions.value.find { it.id == event.sessionId }
                    session?.parentId ?: event.sessionId
                } else {
                    event.sessionId
                }
                Log.i(TAG, "[${server.displayName}] Permission asked: ${event.permission} (session=${event.sessionId}, target=$targetSessionId)")
                if (sessionFocusHolder.shouldSuppress(server.id, targetSessionId)) return
                appNotificationManager.showPermissionNotification(
                    this, systemNotificationManager, server, targetSessionId, event.permission
                )
            }
            is SseEvent.QuestionAsked -> {
                val targetSessionId = if (appNotificationManager.isChildSession(event.sessionId)) {
                    // 子 session 问题冒泡到父 session 通知
                    val session = eventDispatcher.sessions.value.find { it.id == event.sessionId }
                    session?.parentId ?: event.sessionId
                } else {
                    event.sessionId
                }
                Log.i(TAG, "[${server.displayName}] Question asked for session ${event.sessionId} (target=$targetSessionId)")
                if (sessionFocusHolder.shouldSuppress(server.id, targetSessionId)) return
                val questionText = event.questions.firstOrNull()?.question
                    ?: getString(R.string.notification_has_question, getString(R.string.notification_new_session))
                appNotificationManager.showQuestionNotification(
                    this, systemNotificationManager, server, targetSessionId, questionText
                )
            }
            is SseEvent.SessionError -> {
                val targetSessionId = if (event.sessionId != null && appNotificationManager.isChildSession(event.sessionId)) {
                    // 子 session 错误冒泡到父 session 通知
                    val session = eventDispatcher.sessions.value.find { it.id == event.sessionId }
                    session?.parentId ?: event.sessionId
                } else {
                    event.sessionId
                }
                Log.i(TAG, "[${server.displayName}] Session error: ${event.error} (session=${event.sessionId}, target=$targetSessionId)")
                if (targetSessionId != null && sessionFocusHolder.shouldSuppress(server.id, targetSessionId)) return
                appNotificationManager.showErrorNotification(
                    this, systemNotificationManager, server, targetSessionId, event.error
                )
            }
            else -> { }
        }
    }

    // ============ Notification Watchdog ============

    private fun startNotificationWatchdog() {
        if (notificationWatchdogJob?.isActive == true) return
        notificationWatchdogJob = serviceScope.launch {
            while (isActive && connectionManager.connections.isNotEmpty()) {
                delay(5_000)
                if (!isNotificationVisible()) {
                    Log.i(TAG, "Foreground notification was dismissed, restoring it")
                    val notification = appNotificationManager.createPersistentNotification(
                        this@OpenCodeConnectionService, connectionManager.connections, ::isLocalServer
                    )
                    startForeground(AppNotificationManager.PERSISTENT_NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private fun isNotificationVisible(): Boolean {
        return systemNotificationManager.activeNotifications.any {
            it.id == AppNotificationManager.PERSISTENT_NOTIFICATION_ID
        }
    }

    // ============ WakeLock ============

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            acquire()
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                if (BuildConfig.DEBUG) Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    // ============ Helpers ============

    private fun isLocalServer(server: ServerConfig): Boolean {
        val normalizedUrl = server.url.trim().lowercase(Locale.US).removeSuffix("/")
        if (normalizedUrl == LocalServerManager.LOCAL_SERVER_URL.lowercase(Locale.US)) return true

        val host = server.host.lowercase(Locale.US)
        val port = server.port
        return (host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]") &&
            port == 4096
    }

    private fun updatePersistentNotification() {
        appNotificationManager.updatePersistentNotification(
            this, systemNotificationManager, connectionManager.connections, ::isLocalServer
        )
    }

    companion object {
        const val ACTION_OPEN_SESSION = "dev.leonardo.octether.OPEN_SESSION"
        const val ACTION_DISCONNECT = "dev.leonardo.octether.DISCONNECT"
        const val ACTION_DISCONNECT_ALL = "dev.leonardo.octether.DISCONNECT_ALL"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_SERVER_USERNAME = "server_username"
        const val EXTRA_SERVER_PASSWORD = "server_password"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_SESSION_PATH = "session_path"
        const val EXTRA_SESSION_ID = "sessionId"
    }
}
