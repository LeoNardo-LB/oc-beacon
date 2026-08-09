package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessagePage
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.util.CursorCodec
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

private const val TAG = "MessagePaginationUseCase"

/** 翻页加载更早消息的来源。 */
enum class LoadOlderSource { ARCHIVE, NETWORK }

/** loadOlderMessages 的返回值：消息列表 + 来源（决定 Delegate 是否落热表）。 */
data class LoadOlderResult(
    val messages: List<MessageWithParts>,
    val source: LoadOlderSource,
)

class MessagePaginationUseCase @Inject constructor(
    private val chatRepository: ChatRepository,
    private val sessionRepository: SessionRepository,
    private val messageStore: MessageCacheRepository,
) {
    fun observeMessages(sessionId: String): Flow<List<Message>> =
        chatRepository.getMessagesFlow(sessionId)

    /**
     * 进入会话加载：缓存优先。
     * 本地有缓存 → 返回本地 + REST 增量（before=本地最旧游标）合并；
     * 本地为空 → 全量拉取。
     * 网络失败且本地有缓存 → 回退返回本地缓存（离线可浏览，不显示空）；
     * 网络失败且本地无缓存 → 返回 failure（UI 显示加载失败态）。
     */
    suspend fun loadMessagesForSession(
        serverId: String,
        sessionId: String,
        limit: Int,
    ): Result<List<MessageWithParts>> {
        val local = messageStore.loadRange(sessionId, limit, beforeId = null)
        val oldestId = messageStore.oldestMessageId(sessionId)
        return runCatching {
            // 本地有缓存时，只拉取本地最旧游标之后的新消息
            val before = oldestId?.let { id ->
                val created = messageStore.messageCreatedAt(id)
                if (created != null) CursorCodec.encode(id, created) else null
            }
            val page = sessionRepository.listMessages(serverId, sessionId, limit, before = before)
                .getOrThrow()
            messageStore.upsertMessages(sessionId, page.messages, persistOldBeyondWindow = false)
            mergeLocalAndRemote(local, page.messages)
        }.recoverCatching { e ->
            // 网络失败回退：本地有缓存则返回缓存（缓存优先理念），无缓存保持失败
            if (local.isNotEmpty()) {
                AppLogger.w(TAG, "Network load failed, falling back to ${local.size} cached messages", e)
                local
            } else {
                throw e
            }
        }
    }

    /**
     * 翻页加载更早：本地归档优先；归档读尽 → 走网络。
     *
     * - beforeId 非空且 hasArchivedMessages → loadArchivedRange；
     *   非空 → 直接返回 [LoadOlderSource.ARCHIVE]（不调网络、不落热表，防死循环）。
     * - 归档空 → 网络（[LoadOlderSource.NETWORK]，落热表，现有逻辑）。
     */
    suspend fun loadOlderMessages(
        serverId: String,
        sessionId: String,
        limit: Int,
        beforeId: String?,
    ): Result<LoadOlderResult> {
        // 本地归档优先：before 游标对应的 created 之前的归档桶
        val beforeCreated = beforeId?.let { messageStore.messageCreatedAt(it) }
        if (beforeCreated != null && messageStore.hasArchivedMessages(sessionId, beforeCreated)) {
            val archived = messageStore.loadArchivedRange(sessionId, limit, beforeCreated)
            if (archived.isNotEmpty()) {
                AppLogger.d(TAG, "[paging] session=$sessionId: ${archived.size} older msgs from archive (before=$beforeCreated)")
                return Result.success(LoadOlderResult(archived, LoadOlderSource.ARCHIVE))
            }
        }
        // 归档读尽 → 网络
        return runCatching {
            // before 游标需要 base64url 编码（裸 ID 服务端不识别）
            val before = beforeId?.let { id ->
                val created = messageStore.messageCreatedAt(id)
                if (created != null) CursorCodec.encode(id, created) else null
            }
            val page = sessionRepository.listMessages(serverId, sessionId, limit, before = before)
                .getOrThrow()
            messageStore.upsertMessages(sessionId, page.messages, persistOldBeyondWindow = false)
            LoadOlderResult(page.messages, LoadOlderSource.NETWORK)
        }
    }

    private fun mergeLocalAndRemote(
        local: List<MessageWithParts>,
        remote: List<MessageWithParts>,
    ): List<MessageWithParts> {
        val byId = (local + remote).associateBy { it.info.id }
        return byId.values.sortedBy { it.info.time.created }
    }
}
