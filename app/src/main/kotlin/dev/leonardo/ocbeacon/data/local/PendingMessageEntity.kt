package dev.leonardo.ocbeacon.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 堆积消息（turn 结束后待发送的本地暂存消息，设计定稿 2026-08-20）。
 *
 * - 严格按会话作用域；position 为会话内顺序（0 起，插入时 max+1）。
 * - 仅纯文本（v1 设计：附件消息不 enqueue）。
 * - 会话删除的级联清理由 PendingMessageRepositoryImpl.deleteForSession
 *   在会话删除路径上显式调用（本库无 sessions 表，不能用外键级联）。
 */
@Entity(
    tableName = "pending_messages",
    indices = [Index(value = ["sessionId", "position"])],
)
data class PendingMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val position: Int,
    val text: String,
    val createdAt: Long,
)
