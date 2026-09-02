package dev.leonardo.ocbeacon.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * #306：本地缓存的会话列表条目（兜底展示用）。payload 为完整 Session JSON
 * （kotlinx.serialization，Telegram BLOB 化——对齐 [CachedMessageEntity] 模式）。
 *
 * 写入时机：REST 全量基线（setSessions——loadSessions/preLoadSessions/删除后重拉）
 * 时 replaceForServer 全量替换；SSE 增量不写缓存（断连兜底显示允许标题等
 * 字段陈旧到「上次 REST 拉取」，总比白屏好——设计取舍见 backlog #306）。
 *
 * 读取时机：getSessionsFlow 内存态为空（冷启动/断连 clearForServer 后）时
 * 作为展示兜底（[dev.leonardo.ocbeacon.data.repository.SessionRepositoryImpl]）。
 */
@Entity(
    tableName = "cached_sessions",
    indices = [
        // serverId 等值 + updatedAt DESC——observeByServer 的查询序即索引序
        Index(value = ["serverId", "updatedAt"]),
    ],
)
data class CachedSessionEntity(
    @PrimaryKey val id: String,          // ses_ 会话 id（全局唯一，跨服务器不撞）
    val serverId: String,                // 归属服务器（兜底流按此过滤）
    val updatedAt: Long,                 // time.updated 毫秒，排序键
    val payload: String,                 // 完整 Session JSON
)
