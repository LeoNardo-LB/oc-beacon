package dev.leonardo.ocbeacon.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地缓存的消息部件。独立成表：SSE 流式更新每 48ms 一个 token delta，
 * 独立表每次只更新单行 text，避免重写整条消息 JSON 的写放大。
 */
@Entity(
    tableName = "cached_parts",
    foreignKeys = [
        ForeignKey(
            entity = CachedMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["messageId"]), Index(value = ["sessionId"])],
)
data class CachedPartEntity(
    @PrimaryKey val id: String,          // part id
    val messageId: String,               // FK → cached_messages.id
    val sessionId: String,
    val type: String,                    // text / tool / code 等
    val text: String?,                   // 文本内容（流式更新热点）
    val payload: String?,                // 完整 Part JSON（保留扩展字段）
)
