package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.local.PendingMessageDao
import dev.leonardo.ocbeacon.data.local.PendingMessageEntity
import dev.leonardo.ocbeacon.domain.model.PendingMessage
import dev.leonardo.ocbeacon.domain.repository.PendingMessageRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 堆积消息仓库实现（Room 持久化，跨重启保留）。
 *
 * 重启语义（设计定稿 Q4a）：恢复后不主动补发——队列静默等待该会话下一次
 * 自然成功 turn 结束照常推进；空闲会话的唯一主动放行口是面板「继续」按钮。
 */
@Singleton
class PendingMessageRepositoryImpl @Inject constructor(
    private val dao: PendingMessageDao,
    private val clock: () -> Long,
) : PendingMessageRepository {

    override fun observeQueue(sessionId: String): Flow<List<PendingMessage>> =
        dao.observeQueue(sessionId).map { list -> list.map { it.toDomain() } }

    override suspend fun enqueue(sessionId: String, text: String) {
        dao.appendToTail(sessionId, text, clock())
    }

    override suspend fun updateText(id: Long, text: String) = dao.updateText(id, text)

    override suspend fun delete(id: Long) = dao.deleteById(id)

    override suspend fun clear(sessionId: String) = dao.deleteForSession(sessionId)

    override suspend fun reorder(sessionId: String, orderedIds: List<Long>) {
        val snapshot = dao.snapshotQueue(sessionId)
        val knownIds = snapshot.mapTo(HashSet()) { it.id }
        // 只重排属于该会话的条目（防御 UI 过期 id）；未提及的条目追加尾部不丢失
        val filtered = orderedIds.filter { it in knownIds }
        val remaining = snapshot.map { it.id }.filterNot { it in filtered.toHashSet() }
        if (filtered.isNotEmpty() || remaining.isNotEmpty()) {
            dao.applyOrder(filtered + remaining)
        }
    }

    override suspend fun dequeueHead(sessionId: String): PendingMessage? =
        dao.dequeueHead(sessionId)?.toDomain()

    override suspend fun deleteForSession(sessionId: String) = dao.deleteForSession(sessionId)
}

private fun PendingMessageEntity.toDomain() = PendingMessage(
    id = id,
    sessionId = sessionId,
    position = position,
    text = text,
    createdAt = createdAt,
)
