package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.ApiVersion
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

/**
 * loadAround（快速导航定位加载）的返回值。
 *
 * - [target]：定位目标消息（单独获取以保证其在 displayItems 中）。
 * - [olderMessages]：目标之前（更旧）的消息。
 * - [newerMessages]：目标之后（更新）的消息（V1 恒为空——V1 不支持更新方向）。
 * - [olderNextCursor]：older 方向的服务器游标（V2 cursor.next；用于后续 loadOlder）。
 * - [newerPreviousCursor]：newer 方向的服务器游标（V2 cursor.previous；用于后续 loadNewer）。
 */
data class LoadAroundResult(
    val target: MessageWithParts,
    val olderMessages: List<MessageWithParts>,
    val newerMessages: List<MessageWithParts>,
    val olderNextCursor: String? = null,
    val newerPreviousCursor: String? = null,
)

/** loadNewerMessages 的返回值。 */
data class LoadNewerResult(
    val messages: List<MessageWithParts>,
    /** 下一页更新方向游标（V2 cursor.previous；为空表示已到最新）。 */
    val previousCursor: String? = null,
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
            // 2026-08-15 修复（统计栏只剩 token 圆圈——历史损坏数据无法自愈）：
            // 0.3.1-dev.1/2 时代 REST_AUTHORITY 纯覆盖把 assistant 的 model/
            // tokens 抹掉的旧消息已固化在 Room。增量游标（before=本地最旧）
            // 只拉新消息，损坏的旧消息永远不会被 REST 重新拉取修复。
            // 检测到缓存中存在元数据损坏（assistant 无 modelId）→ 本次改走
            // 全量拉取（before=null）→ REST 带回 model → Room REPLACE 落库
            // + SSE_PRIORITY 的 mergeMessageMeta(withMeta) 修复内存。
            // 修复后缓存干净，下次进入恢复增量路径（一次性代价）。
            val hasDamagedMeta = local.any { m ->
                m.info is dev.leonardo.ocbeacon.domain.model.Message.Assistant &&
                    m.info.modelId == null
            }
            // 本地有缓存时，只拉取本地最旧游标之后的新消息
            // 2026-08-16 根治（cursor 400 → 增量静默失效）：原实现无条件用 V1
            // 格式 CursorCodec.encode(id,time)，经 MessageApiImpl V2 分支以
            // cursor 参数名发送 → 部署版 V2 服务器对 V1 格式 cursor 直接 400
            //（curl 三组对照实证）→ 空页 → mergeLocalAndRemote 回退本地，
            // 增量同步静默失效。且 curl 进一步实证：V2 cursor 是**服务器窗口
            // 语义**——本地构造的 V2 格式 cursor 换任意老消息 id 也返回空页
            //（仅近期窗口内 id 有效），本地构造锚点根本不可靠。
            // 根治：V2 增量不传 cursor（拉最新 limit 窗口），mergeLocalAndRemote
            // 按 id 去重合并——语义等价（增量=刷新最新窗口）且不依赖锚点有效性。
            val before = if (hasDamagedMeta || isV2Server(serverId)) null else oldestId?.let { id ->
                val created = messageStore.messageCreatedAt(id)
                if (created != null) CursorCodec.encode(id, created) else null
            }
            if (hasDamagedMeta) {
                AppLogger.i(TAG, "Session $sessionId has assistant messages without modelId (legacy overwrite damage), doing full refresh to repair")
            }
            val page = sessionRepository.listMessages(serverId, sessionId, limit, before = before)
                .getOrThrow()
            // 2026-08-16（缺 Q 根治·第 3 层一致化）：进会话增量同样落库
            //（与 loadOlderMessages 对齐；窗口过滤已保护归档分层）。
            messageStore.upsertMessages(sessionId, page.messages, persistOldBeyondWindow = true)
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
                // 2026-08-16（缺 Q 根治·第 3 层：翻页落库）：浏览过的更早消息也落 Room
                //（persistOldBeyondWindow=true）——快速定位/重进的本地数据覆盖
                // 用户浏览范围。原 false 是防 prune 循环的旧设计：归档游标
                //（FSM Archive）与网络游标（Network）分离后该顾虑已消除
                //（#56 修复），且 upsertMessages 的窗口过滤（热表最旧之前跳过）
                // 已保护归档分层。
                messageStore.upsertMessages(sessionId, page.messages, persistOldBeyondWindow = true)
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
                // 2026-08-16（缺 Q 根治·第 3 层：翻页落库）：浏览过的更早消息也落 Room
                //（persistOldBeyondWindow=true）——快速定位/重进的本地数据覆盖
                // 用户浏览范围。原 false 是防 prune 循环的旧设计：归档游标
                //（FSM Archive）与网络游标（Network）分离后该顾虑已消除
                //（#56 修复），且 upsertMessages 的窗口过滤（热表最旧之前跳过）
                // 已保护归档分层。
                messageStore.upsertMessages(sessionId, page.messages, persistOldBeyondWindow = true)
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
        // 归档读尽 → 网络首次翻页。2026-08-16 根治（cursor 400）：
        // V2 服务器对 V1 格式 cursor（经 cursor 参数名）返回 400；且本地构造
        // V2 格式 cursor 也不可靠（服务器窗口语义——仅近期 id 有效，curl 实证
        // 换中部历史 id 返回空页）。故 V2 首次翻页**不传 cursor**：服务器返回
        // 最新窗口 + 原生 cursor.next（与已加载内容重叠，APPEND_ONLY 去重），
        // 响应携带的 nextCursor 进 FSM Network 态后，后续翻页走 networkCursor
        // 分支用服务器原生游标（唯一可靠模式）。
        // V1 保持本地 CursorCodec.encode（before 参数语义，实测有效）。
        return runCatching {
            val isV2 = isV2Server(serverId)
            val before = if (isV2) null else beforeId?.let { id ->
                val msgCreated = messageStore.messageCreatedAt(id)
                if (msgCreated != null) CursorCodec.encode(id, msgCreated) else null
            }
            val page = sessionRepository.listMessages(serverId, sessionId, limit, before = before)
                .getOrThrow()
            messageStore.upsertMessages(sessionId, page.messages, persistOldBeyondWindow = true)
            LoadOlderResult(page.messages, LoadOlderSource.NETWORK, nextCursor = page.nextCursor)
        }
    }

    /**
     * 快速导航定位加载：以 [targetMessageId] 为中心，前后各 [limit] 条双向加载。
     *
     * - **V2**：构造双向 cursor（{id:target, direction:next/previous}）分别请求更旧/更新；
     *   目标本身不在 cursor 结果中，单独 getMessage 获取。三批消息合并 upsert（APPEND_ONLY
     *   按 id 去重，见 MessageEventHandler.mergeSortedMessages）。
     * - **V1 降级**：仅单条 target + before 向旧加载 [limit] 条；更新方向不可用
     *   （V1 无 after/cursor，依赖本地缓存；newerMessages 为空、newerPreviousCursor=null）。
     *
     * 返回的双向游标供 Delegate 写入 FSM（AroundLoaded）：后续滚动分页（older/newer）复用。
     *
     * SSE 竞态：mergeSortedMessages 已按 id 去重，SSE 实时新消息与定位加载合并不重复。
     */
    suspend fun loadAround(
        serverId: String,
        sessionId: String,
        targetMessageId: String,
        limit: Int,
    ): Result<LoadAroundResult> = runCatching {
        val isV2 = sessionRepository.getApiVersion(serverId).isV2
        // 单条目标（cursor 结果不含目标本身）
        val target = sessionRepository.getMessage(serverId, sessionId, targetMessageId).getOrThrow()

        if (isV2) {
            // V2 双向 cursor：direction="next"=更旧，"previous"=更新
            val olderCursor = CursorCodec.encodeV2(targetMessageId, CursorCodec.V2Direction.OLDER)
            val newerCursor = CursorCodec.encodeV2(targetMessageId, CursorCodec.V2Direction.NEWER)
            val olderPage = sessionRepository.listMessages(serverId, sessionId, limit, before = olderCursor).getOrThrow()
            val newerPage = sessionRepository.listMessages(serverId, sessionId, limit, before = newerCursor).getOrThrow()
            // 合并 upsert（target + older + newer；APPEND_ONLY 路径按 id 去重）
            val all = listOf(target) + olderPage.messages + newerPage.messages
            messageStore.upsertMessages(sessionId, all, persistOldBeyondWindow = true)
            LoadAroundResult(
                target = target,
                olderMessages = olderPage.messages,
                newerMessages = newerPage.messages,
                olderNextCursor = olderPage.nextCursor,
                newerPreviousCursor = newerPage.previousCursor,
            )
        } else {
            // V1 降级：target + before 向旧加载
            val before = CursorCodec.encode(targetMessageId, target.info.time.created)
            val olderPage = sessionRepository.listMessages(serverId, sessionId, limit, before = before).getOrThrow()
            val all = listOf(target) + olderPage.messages
            messageStore.upsertMessages(sessionId, all, persistOldBeyondWindow = true)
            LoadAroundResult(
                target = target,
                olderMessages = olderPage.messages,
                newerMessages = emptyList(),
                olderNextCursor = olderPage.nextCursor,
                newerPreviousCursor = null,
            )
        }
    }

    /**
     * 向更新方向加载（定位到中间后，向下滑触发）。
     *
     * [newerServerCursor] 为上次 newer 方向请求返回的 cursor.previous（V2）。
     * 为空（V1 或已读尽）→ 返回空结果（Delegate 不应在此状态下调用）。
     */
    suspend fun loadNewerMessages(
        serverId: String,
        sessionId: String,
        limit: Int,
        newerServerCursor: String?,
    ): Result<LoadNewerResult> = runCatching {
        if (newerServerCursor == null) return@runCatching LoadNewerResult(emptyList(), null)
        val page = sessionRepository.listMessages(serverId, sessionId, limit, before = newerServerCursor)
            .getOrThrow()
        messageStore.upsertMessages(sessionId, page.messages, persistOldBeyondWindow = false)
        LoadNewerResult(page.messages, page.previousCursor)
    }

    /**
     * 当前服务器是否为 V2 API（支持双向 cursor 翻页：next=更旧 / previous=更新）。
     *
     * 供 Delegate 的本地优先分支（loadAroundFromLocal）判断 newer 方向自定义 cursor
     * 是否可用：V2 → 构造自定义 cursor 启用下滑自动加载更新；V1 → 保持 newerCursor=null
     *（V1 协议无 after/cursor 能力，更新方向不可用）。
     */
    suspend fun isV2Server(serverId: String): Boolean =
        sessionRepository.getApiVersion(serverId).isV2

    private fun mergeLocalAndRemote(
        local: List<MessageWithParts>,
        remote: List<MessageWithParts>,
    ): List<MessageWithParts> {
        // 2026-08-15：同 id 冲突默认 local 优先（本地流式数据更新），但 local
        // 是元数据损坏的 assistant（modelId 缺失——历史覆盖损坏）时采用 remote
        // （REST 权威修复版），否则全量修复拉取的结果被本地损坏版再次覆盖。
        val byId = LinkedHashMap<String, MessageWithParts>()
        for (m in local) {
            byId[m.info.id] = m
        }
        for (m in remote) {
            val existing = byId[m.info.id]
            byId[m.info.id] = when {
                existing == null -> m
                existing.info is dev.leonardo.ocbeacon.domain.model.Message.Assistant &&
                    existing.info.modelId == null -> m
                else -> existing
            }
        }
        return byId.values.sortedBy { it.info.time.created }
    }
}
