package dev.leonardo.ocbeacon.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地缓存的会话消息。payload 为完整 Message JSON（kotlinx.serialization）。
 * 索引列仅提取分页/查询所需字段，避免 30+ 字段拆列（Telegram 同款 BLOB 化）。
 */
@Entity(
    tableName = "cached_messages",
    indices = [Index(value = ["sessionId", "created"])],
)
data class CachedMessageEntity(
    @PrimaryKey val id: String,          // msg_ ULID，单调递增，去重/游标
    val sessionId: String,               // ses_ ULID
    val created: Long,                   // time.created 毫秒，排序键
    val role: String,                    // user / assistant
    val payload: String,                 // 完整 Message JSON
)
