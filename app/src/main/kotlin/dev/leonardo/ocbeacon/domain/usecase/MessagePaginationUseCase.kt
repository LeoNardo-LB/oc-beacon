package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.BuildConfig
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
    /**
     * 服务器返回的下一页游标（仅 NETWORK 来源非空；V2 = cursor.next 字符串）。
     * Delegate 存入 PaginationFSM.Network.serverCursor，下次翻页直接透传。
     */
    val nextCursor: String? = null,
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
     * - [beforeCreated] 非空（归档时间游标）时优先用它查询归档；
     *   hasArchivedMessages → loadArchivedRange；非空 → 直接返回 [LoadOlderSource.ARCHIVE]
     *   （不调网络、不落热表，防死循环）。
     * - [networkCursor] 非空（V2 服务器游标，2026-08-11 新增）时**跳过归档检查**
     *   直接走网络——该游标是"归档已读尽后的网络边界"，再查归档只会读到重复桶；
     *   网络请求的 cursor 参数直接透传服务器游标（本地 CursorCodec 格式 V2 不兼容）。
     * - [networkBeforeCreated] 非空（V1 网络游标时间，2026-08-10 新增）时**跳过归档检查**
     *   直接走网络，before 用 CursorCodec.encode(id, networkBeforeCreated)。
     * - 两者都为空时回落到 [beforeId] 在热表的时间；归档空 → 网络。
     */
    suspend fun loadOlderMessages(
        serverId: String,
        sessionId: String,
        limit: Int,
        beforeId: String?,
        beforeCreated: Long? = null,
        networkBeforeCreated: Long? = null,
        networkCursor: String? = null,
    ): Result<LoadOlderResult> {
        // 网络分页游标（V2 服务器游标优先）：跳过归档直接走网络（游标本身是归档读尽后的边界）
        if (networkCursor != null) {
            return runCatching {
                val page = sessionRepository.listMessages(serverId, sessionId, limit, before = networkCursor)
                    .getOrThrow()
                messageStore.upsertMessages(sessionId, page.messages, persistOldBeyondWindow = false)
                LoadOlderResult(page.messages, LoadOlderSource.NETWORK, nextCursor = page.nextCursor)
            }
        }
        // 网络分页游标（V1）：跳过归档直接走网络（游标本身是归档读尽后的边界）
        if (networkBeforeCreated != null) {
            return runCatching {
                val before = CursorCodec.encode(
                    beforeId ?: error("networkBeforeCreated requires beforeId"),
                    networkBeforeCreated,
                )
                val page = sessionRepository.listMessages(serverId, sessionId, limit, before = before)
                    .getOrThrow()
                messageStore.upsertMessages(sessionId, page.messages, persistOldBeyondWindow = false)
                LoadOlderResult(page.messages, LoadOlderSource.NETWORK, nextCursor = page.nextCursor)
            }
        }
        // 归档时间游标优先；否则从热表查 beforeId 对应时间
        val created = beforeCreated ?: beforeId?.let { messageStore.messageCreatedAt(it) }
        if (created != null && messageStore.hasArchivedMessages(sessionId, created)) {
            val archived = messageStore.loadArchivedRange(sessionId, limit, created)
            if (archived.isNotEmpty()) {
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "[paging] session=$sessionId: ${archived.size} older msgs from archive (before=$created)")
                }
                return Result.success(LoadOlderResult(archived, LoadOlderSource.ARCHIVE))
            }
        }
        // 归档读尽 → 网络（首次翻页：无服务器游标，用本地 CursorCodec 编码——V2 服务器会忽略
        // 该格式并返回最新数据；首次翻页冗余一次请求，响应携带 cursor.next 后后续翻页正常）
        return runCatching {
            // before 游标需要 base64url 编码（裸 ID 服务端不识别）
            val before = beforeId?.let { id ->
                val msgCreated = messageStore.messageCreatedAt(id)
                if (msgCreated != null) CursorCodec.encode(id, msgCreated) else null
            }
            val page = sessionRepository.listMessages(serverId, sessionId, limit, before = before)
                .getOrThrow()
            messageStore.upsertMessages(sessionId, page.messages, persistOldBeyondWindow = false)
            LoadOlderResult(page.messages, LoadOlderSource.NETWORK, nextCursor = page.nextCursor)
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
