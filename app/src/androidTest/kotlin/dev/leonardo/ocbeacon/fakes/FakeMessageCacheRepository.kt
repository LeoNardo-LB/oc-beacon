package dev.leonardo.ocbeacon.fakes

import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-08-16：androidTest Hilt 测试图的 MessageCacheRepository Fake。
 *
 * 背景：androidTest 源集此前从未编译通过（Fake 接口漂移积累），主图的
 * MessageStore 依赖 Room DAO（测试图不含数据库基建），MessagePaginationUseCase
 * 的 MessageCacheRepository 绑定缺失 → Hilt 测试图 Dagger/MissingBinding。
 * 本 Fake 以内存 Map 支撑测试语义。
 */
@Singleton
class FakeMessageCacheRepository @Inject constructor() : MessageCacheRepository {

    private val messagesBySession = MutableStateFlow<Map<String, List<MessageWithParts>>>(emptyMap())

    override suspend fun appendPartTexts(
        sessionId: String,
        messages: List<MessageWithParts>,
        deltas: List<dev.leonardo.ocbeacon.data.local.PartDelta>,
    ) = Unit

    override suspend fun updatePartText(sessionId: String, partId: String, text: String) = Unit

    override suspend fun upsertMessages(
        sessionId: String,
        messages: List<MessageWithParts>,
        persistOldBeyondWindow: Boolean,
    ) {
        messagesBySession.value = messagesBySession.value + (sessionId to messages)
    }

    override fun observeMessages(sessionId: String): Flow<List<MessageWithParts>> =
        messagesBySession.map { it[sessionId].orEmpty() }

    override suspend fun loadRange(sessionId: String, limit: Int, beforeId: String?): List<MessageWithParts> =
        messagesBySession.value[sessionId].orEmpty().takeLast(limit)

    override suspend fun loadRangeNewer(sessionId: String, limit: Int, afterId: String): List<MessageWithParts> =
        emptyList()

    override suspend fun userMessages(sessionId: String, limit: Int): List<MessageWithParts> =
        messagesBySession.value[sessionId].orEmpty().filter { it.info.role == "user" }.takeLast(limit)

    override suspend fun messageById(sessionId: String, messageId: String): MessageWithParts? =
        messagesBySession.value[sessionId].orEmpty().firstOrNull { it.info.id == messageId }

    override suspend fun oldestMessageId(sessionId: String): String? =
        messagesBySession.value[sessionId].orEmpty().minByOrNull { it.info.time.created }?.info?.id

    override suspend fun messageCreatedAt(messageId: String): Long? = null

    override suspend fun clearSession(sessionId: String) {
        messagesBySession.value = messagesBySession.value - sessionId
    }

    override suspend fun replaceSessionMessages(sessionId: String, messages: List<MessageWithParts>) {
        messagesBySession.value = messagesBySession.value + (sessionId to messages)
    }

    override suspend fun loadArchivedRange(sessionId: String, limit: Int, beforeCreated: Long): List<MessageWithParts> =
        emptyList()

    override suspend fun hasArchivedMessages(sessionId: String, beforeCreated: Long): Boolean = false

    // #223/#230 空 part 清扫（接口 2026-08-26 扩展）：内存 Fake 无空 part 累积，零动作
    override suspend fun sweepEmptyStreamParts(): Int = 0
}
