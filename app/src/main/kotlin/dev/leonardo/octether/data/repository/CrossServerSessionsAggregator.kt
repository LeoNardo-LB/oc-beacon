package dev.leonardo.octether.data.repository

import dev.leonardo.octether.data.api.session.SessionApi
import dev.leonardo.octether.domain.model.ServerConnection
import dev.leonardo.octether.domain.model.Session
import dev.leonardo.octether.service.SseConnectionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aggregates live sessions across every currently-connected server.
 *
 * This is the cross-server data source for the Cross-Server Favorites screen. It is kept
 * **independent of [EventDispatcher]** — the dispatcher is single-server oriented (state for the
 * active connection only), whereas this aggregator needs a per-server snapshot of every connected
 * server at once. Sessions are fetched via [SessionApi] REST calls.
 *
 * Reactivity model:
 * - The only trigger is [SseConnectionManager.connectedServerIds] — when a server connects or
 *   disconnects, the map is rebuilt.
 * - Server configs are resolved once per rebuild via `servers.first()` (we do **not** subscribe to
 *   the servers flow, so routine health-check updates to [dev.leonardo.octether.domain.model.ServerConfig]
 *   do not cause redundant re-fetches).
 * - Per-server fetches run concurrently; a failure on one server yields an empty list without
 *   affecting the others.
 *
 * Emits `Map<serverId, List<Session>>`. Disconnected servers are absent from the map — callers
 * detect offline favorites via the `isConnected` flag derived from [SseConnectionManager.connectedServerIds].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CrossServerSessionsAggregator @Inject constructor(
    private val sseConnectionManager: SseConnectionManager,
    private val serverDataStore: ServerDataStore,
    private val sessionApi: SessionApi,
) {
    /**
     * Per-server sessions for every currently-connected server.
     * Key = serverId, Value = that server's sessions (empty list on fetch failure).
     */
    val crossServerSessions: Flow<Map<String, List<Session>>> =
        sseConnectionManager.connectedServerIds
            .flatMapLatest { connectedIds ->
                if (connectedIds.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    flow {
                        val serverById = serverDataStore.servers.first().associateBy { it.id }
                        val connected = connectedIds.mapNotNull { serverById[it] }
                        if (connected.isEmpty()) {
                            emit(emptyMap())
                            return@flow
                        }
                        val results = coroutineScope {
                            connected.map { server ->
                                async {
                                    val conn = ServerConnection.from(
                                        server.url,
                                        server.username,
                                        server.password,
                                    )
                                    val sessions = runCatching { sessionApi.listSessions(conn) }
                                        .getOrDefault(emptyList())
                                    server.id to sessions
                                }
                            }.awaitAll()
                        }
                        emit(results.toMap())
                    }
                }
            }
}
