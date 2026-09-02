package dev.leonardo.ocbeacon.data.local

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #306：会话列表缓存存取（对齐 [MessageStore] 模式——DAO + 注入 Json）。
 *
 * 写：REST 全量基线（[dev.leonardo.ocbeacon.data.repository.SessionRepositoryImpl.setSessions]）
 * 时 replaceForServer 全量替换——服务器权威快照；SSE 增量不写缓存（兜底显示
 * 允许标题等字段陈旧到「上次 REST 拉取」——取舍见 backlog #306）。
 *
 * 读：内存态无该服务器映射（断连 clearForServer / 冷启动）时兜底展示。
 * 坏 payload 逐条跳过（版本演进/截断防御），绝不整流失败。
 */
@Singleton
class SessionCacheStore @Inject constructor(
    private val dao: CachedSessionDao,
    private val json: Json,
) {

    /** 兜底流：payload 反序列化为 [Session]（updatedAt 降序 = 列表展示序）。 */
    fun observe(serverId: String): Flow<List<Session>> =
        dao.observeByServer(serverId).map { entities ->
            entities.mapNotNull { entity ->
                try {
                    json.decodeFromString<Session>(entity.payload)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Skip malformed cached session ${entity.id.take(12)}: ${e.message}")
                    null
                }
            }
        }

    /** REST 基线全量替换（挂起——调用方自选调度器与异常策略）。 */
    suspend fun cacheSessions(serverId: String, sessions: List<Session>) {
        dao.replaceForServer(serverId, sessions.map { s ->
            CachedSessionEntity(
                id = s.id,
                serverId = serverId,
                updatedAt = s.time.updated,
                payload = json.encodeToString(Session.serializer(), s),
            )
        })
    }

    /** 服务器删除时清孤儿缓存（serverId 不复用，残留行永不可达）。 */
    suspend fun deleteForServer(serverId: String) = dao.deleteByServer(serverId)

    private companion object {
        const val TAG = "SessionCache"
    }
}
