package dev.leonardo.ocbeacon.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 归档桶：一个时间窗口内多条消息的 zstd 压缩 BLOB + 元数据。
 *
 * 注意 [payload] 是 ByteArray：data class 生成的 equals/hashCode 基于引用相等（非内容）。
 * 当前无 Set/Map 去重场景（按 id/查询路径访问），可接受；若未来需内容比较，显式用
 * [ByteArray.contentEquals] / [ByteArray.contentHashCode]。
 */
@Entity(
    tableName = "archive_buckets",
    indices = [Index(value = ["sessionId", "bucketEnd"])],
)
data class ArchiveBucketEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val bucketStart: Long,
    val bucketEnd: Long,
    val messageCount: Int,
    val uncompressedSize: Int,
    val payload: ByteArray,
    val createdAt: Long,
    val lastAccessedAt: Long,
)
