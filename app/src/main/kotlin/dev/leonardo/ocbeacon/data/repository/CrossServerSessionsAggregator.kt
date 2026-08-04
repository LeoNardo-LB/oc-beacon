package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.service.SseConnectionManager
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
 * 聚合每个当前已连接服务器的活跃会话。
 *
 * 这是跨服务器收藏屏幕的跨服务器数据源。它与
 * **[EventDispatcher] 相互独立**——dispatcher 面向单服务器
 *（仅维护活跃连接的状态），而此聚合器需要同时获取每个已连接服务器的
 * 按服务器快照。会话通过 [SessionApi] REST 调用获取。
 *
 * 响应式模型：
 * - 唯一触发源是 [SseConnectionManager.connectedServerIds]——当某服务器
 *   连接或断开时，map 会被重建。
 * - 服务器配置在每次重建时通过 `servers.first()` 解析一次（我们**不**订阅
 *   servers flow，因此 [dev.leonardo.ocbeacon.domain.model.ServerConfig] 的
 *   常规健康检查更新不会导致冗余的重新拉取）。
 * - 按服务器的拉取并发执行；某服务器失败返回空列表，不影响其他服务器。
 *
 * 发射 `Map<serverId, List<Session>>`。已断开的服务器不在 map 中——调用方
 * 通过派生自 [SseConnectionManager.connectedServerIds] 的 `isConnected` 标志
 * 检测离线收藏。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CrossServerSessionsAggregator @Inject constructor(
    private val sseConnectionManager: SseConnectionManager,
    private val serverDataStore: ServerDataStore,
    private val sessionApi: SessionApi,
) {
    /**
     * 每个当前已连接服务器的按服务器会话。
     * 键 = serverId，值 = 该服务器的会话（拉取失败时为空列表）。
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
