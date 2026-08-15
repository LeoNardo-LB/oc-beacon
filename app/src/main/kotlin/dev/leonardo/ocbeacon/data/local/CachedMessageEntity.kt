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
    indices = [
        Index(value = ["sessionId", "created"]),
        // 2026-08-16 v3：导航/翻页查询索引（sessionId 等值 + created DESC + id DESC
        // tie-breaker，与 userMessages/loadRange 的 ORDER BY 完全对齐；role 过滤
        // 在索引结果上做）。Room 2.8 Index 注解不支持部分索引（WHERE 子句），
        // 此处为普通复合索引，MIGRATION_2_3 创建同名索引保证 schema 校验通过。
        Index(value = ["sessionId", "created", "id"]),
    ],
)
data class CachedMessageEntity(
    @PrimaryKey val id: String,          // msg_ ULID，单调递增，去重/游标
    val sessionId: String,               // ses_ ULID
    val created: Long,                   // time.created 毫秒，排序键
    val role: String,                    // user / assistant
    val payload: String,                 // 完整 Message JSON
)
