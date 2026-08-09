package dev.leonardo.ocbeacon.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
