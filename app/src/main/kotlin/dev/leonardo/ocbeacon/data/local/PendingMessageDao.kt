package dev.leonardo.ocbeacon.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** 堆积消息 DAO。排序恒为 (position ASC, id ASC)——position 相同时按插入序稳定排列。 */
@Dao
interface PendingMessageDao {

    @Query("SELECT * FROM pending_messages WHERE sessionId = :sessionId ORDER BY position ASC, id ASC")
    fun observeQueue(sessionId: String): Flow<List<PendingMessageEntity>>

    @Query("SELECT * FROM pending_messages WHERE sessionId = :sessionId ORDER BY position ASC, id ASC LIMIT 1")
    suspend fun peekHead(sessionId: String): PendingMessageEntity?

    /** 原子弹出队首（查+删同事务），供推进管线与「继续」按钮使用。 */
    @Transaction
    suspend fun dequeueHead(sessionId: String): PendingMessageEntity? {
        val head = peekHead(sessionId) ?: return null
        deleteById(head.id)
        return head
    }

    @Query("SELECT COALESCE(MAX(position), -1) FROM pending_messages WHERE sessionId = :sessionId")
    suspend fun maxPosition(sessionId: String): Int

    @Insert
    suspend fun insert(entity: PendingMessageEntity): Long

    /** 会话内追加到队尾（position = max+1，事务内计算防并发重号）。 */
    @Transaction
    suspend fun appendToTail(sessionId: String, text: String, createdAt: Long): PendingMessageEntity {
        val entity = PendingMessageEntity(
            sessionId = sessionId,
            position = maxPosition(sessionId) + 1,
            text = text,
            createdAt = createdAt,
        )
        val id = insert(entity)
        return entity.copy(id = id)
    }

    @Query("UPDATE pending_messages SET text = :text WHERE id = :id")
    suspend fun updateText(id: Long, text: String)

    @Query("DELETE FROM pending_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_messages WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("UPDATE pending_messages SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int)

    /** 拖拽排序：按入参顺序整体重排（0 起）。同事务写入，避免中间态被观察到。 */
    @Transaction
    suspend fun applyOrder(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id -> updatePosition(id, index) }
    }

    @Query("SELECT COUNT(*) FROM pending_messages WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: String): Int

    @Query("SELECT * FROM pending_messages WHERE sessionId = :sessionId ORDER BY position ASC, id ASC")
    suspend fun snapshotQueue(sessionId: String): List<PendingMessageEntity>
}
