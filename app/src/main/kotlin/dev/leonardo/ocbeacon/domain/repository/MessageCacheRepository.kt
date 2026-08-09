package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import kotlinx.coroutines.flow.Flow

/**
 * 消息本地缓存仓库接口（domain 层依赖，data 层实现）。
 *
 * 抽象出 [dev.leonardo.ocbeacon.domain.usecase.MessagePaginationUseCase]、
 * [dev.leonardo.ocbeacon.data.repository.ChatRepositoryImpl]（种子化）
 * 和 [dev.leonardo.ocbeacon.data.repository.handler.MessageEventHandler]（SSE 双写）
 * 所需的本地缓存读写契约，使 domain 层不再直接依赖 data.local.MessageStore，
 * 遵循 Clean Architecture 的依赖方向（UI → Domain ← Data）。
 */
interface MessageCacheRepository {
    suspend fun upsertMessages(
        sessionId: String,
        messages: List<MessageWithParts>,
        persistOldBeyondWindow: Boolean = false,
    )

    fun observeMessages(sessionId: String): Flow<List<MessageWithParts>>

    suspend fun loadRange(sessionId: String, limit: Int, beforeId: String? = null): List<MessageWithParts>

    suspend fun oldestMessageId(sessionId: String): String?

    suspend fun messageCreatedAt(messageId: String): Long?

    suspend fun clearSession(sessionId: String)

    /**
     * 归档读取：查 session 在 [beforeCreated] 之前的归档桶（bucketEnd < beforeCreated），
     * 跨桶解压拼接直到凑满 [limit] 条；读到的桶 touch(lastAccessedAt)。无归档返回 emptyList。
     */
    suspend fun loadArchivedRange(sessionId: String, limit: Int, beforeCreated: Long): List<MessageWithParts>

    /** 是否存在 beforeCreated 之前的归档数据（翻页 hasMore 判断）。 */
    suspend fun hasArchivedMessages(sessionId: String, beforeCreated: Long): Boolean
}
