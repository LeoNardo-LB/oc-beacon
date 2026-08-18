package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.PaginationCursor

/**
 * 分页状态的纯函数有限状态机 —— 参照 SessionStateFSM。
 *
 * 收敛 MessagePaginationDelegate 曾散落的可变状态（#56 TD-1）：
 * archiveCursorCreated / networkCursorId / networkCursorCreated / _hasOlderMessages /
 * autoLoadFailures / autoLoadPausedUntil / _autoLoadPaused 7 个成员 → 单一 [State]。
 *
 * 无状态性：不持有任何可变状态；transition() 是纯函数
 *（给定 state + event → 新 state，无副作用）。
 *
 * 防风暴语义（2026-08-10 引入，逐条保留）：
 * 1. 失败退避：连续失败指数退避（500ms→1s→2s→…，上限 8s）
 * 2. 最大连续失败次数：达 [MAX_AUTO_LOAD_FAILURES] 后暂停自动续载（[State.autoLoadPaused]），
 *    用户手动滑动/重新加载后恢复
 * 3. 成功重置：任何成功加载清零计数与退避
 */
object PaginationFSM {

    /** 自动续载失败退避基础时长（ms）：连续失败指数增长（500→1000→2000→…）。 */
    const val AUTO_LOAD_BACKOFF_BASE_MS = 500L
    /** 退避指数上限（1L shl MAX = 最大 2^4=16 倍 = 8s）。 */
    const val AUTO_LOAD_BACKOFF_MAX_SHIFT = 4
    /** 自动续载最大连续失败次数：达此值暂停自动续载（手动滑动恢复）。 */
    const val MAX_AUTO_LOAD_FAILURES = 3

    data class State(
        /** 下一次 loadOlderMessages 的读取边界。 */
        val cursor: PaginationCursor = PaginationCursor.HotStart,
        /** 服务器上是否存在超出当前限制的更多消息（UI 停止自动分页的依据）。 */
        val hasOlderMessages: Boolean = false,
        /**
         * 下一次 loadNewerMessages 的读取边界（更新方向）。
         * null = 更新方向未激活（正常会话状态：已在最新，无更新可加载）。
         * 由 loadAround 设置；loadNewerMessages 推进；读尽后回落 null。
         */
        val newerCursor: PaginationCursor? = null,
        /** 服务器上是否存在更新方向（newer）的更多消息（UI 停止自动分页的依据）。 */
        val hasNewerMessages: Boolean = false,
        /** 自动续载连续失败次数（成功清零）。 */
        val autoLoadFailures: Int = 0,
        /** 退避截止时间戳（ms）：此前的自动续载触发应等待。 */
        val autoLoadPausedUntil: Long = 0L,
        /** 自动续载暂停（连续失败达上限）——UI 停止自动分页，等待手动触发。 */
        val autoLoadPaused: Boolean = false,
    )

    sealed interface Event {
        /**
         * 会话重新加载（loadMessagesForSession）：游标回落热表边界。
         * @param hasOlderMessages 本次加载是否触及分页边界（messages.size >= limit）。
         */
        data class SessionReloaded(val hasOlderMessages: Boolean) : Event

        /**
         * loadOlderMessages 成功：按来源推进游标 + 更新 hasOlder + 重置防风暴。
         *
         * @param oldestId 本次返回的最老消息 ID（V1 网络游标编码回落用；空页为 null）。
         * @param oldestCreated 本次返回的最老消息 created（ARCHIVE 时间游标 / V1 编码）。
         * @param nextCursor 服务器返回的下一页游标（V2 cursor.next，更早方向；
         *   ARCHIVE 来源或 V1 网络来源为 null）。
         */
        data class LoadSucceeded(
            val source: LoadOlderSource,
            val oldestId: String?,
            val oldestCreated: Long?,
            val nextCursor: String?,
            val pageSize: Int,
            val limit: Int,
        ) : Event

        /** loadOlderMessages 失败（网络/归档损坏）：记录失败 + 指数退避（达上限暂停）。 */
        data class LoadFailed(val now: Long = System.currentTimeMillis()) : Event

        /**
         * loadAround（快速导航定位加载）成功：一次性设置 older + newer 双向游标。
         *
         * 这是一次性"定位加载"——不破坏后续滚动分页的游标语义：
         * - older 游标由调用方根据 older 方向响应构造（V2 = Network.serverCursor=nextCursor；
         *   V1 = Network(id, created)）；后续 loadOlderMessages 正常推进。
         * - newer 游标由调用方根据 newer 方向响应构造（V2 = Network.serverCursor=previousCursor；
         *   V1 = null，更新方向不可用）；后续 loadNewerMessages 推进。
         *
         * @param olderCursor older 方向起始游标（null = 已无更旧）。
         * @param hasOlderMessages older 方向是否还有更多。
         * @param newerCursor newer 方向起始游标（null = 已无更新或 V1 不可用）。
         * @param hasNewerMessages newer 方向是否还有更多。
         */
        data class AroundLoaded(
            val olderCursor: PaginationCursor?,
            val hasOlderMessages: Boolean,
            val newerCursor: PaginationCursor?,
            val hasNewerMessages: Boolean,
        ) : Event

        /**
         * loadNewerMessages 成功：推进 newer 游标 + 更新 hasNewer + 重置防风暴。
         *
         * @param newestId 本次返回的最新消息 ID（V1 回落用；空页为 null）。
         * @param newestCreated 本次返回的最新消息 created（V1 编码回落用）。
         * @param previousCursor 服务器返回的更新方向下一页游标（V2 cursor.previous，更新方向；
         *   V1 恒为 null）。
         */
        data class LoadNewerSucceeded(
            val newestId: String?,
            val newestCreated: Long?,
            val previousCursor: String?,
            val pageSize: Int,
            val limit: Int,
        ) : Event
    }

    fun transition(state: State, event: Event): State = when (event) {
        is Event.SessionReloaded -> state.copy(
            cursor = PaginationCursor.HotStart,
            hasOlderMessages = event.hasOlderMessages,
            // 会话重新加载 → 回到最新边界，更新方向重置（无更新可加载）
            newerCursor = null,
            hasNewerMessages = false,
        )

        is Event.LoadSucceeded -> {
            val cursor = when (event.source) {
                // 归档时间游标推进为本次最老消息 created（下次翻页继续读更早归档）；
                // 空页（不会发生，防御）保持原游标。
                LoadOlderSource.ARCHIVE ->
                    event.oldestCreated?.let { PaginationCursor.Archive(it) } ?: state.cursor
                // 网络游标推进：服务器游标优先（V2）；否则回落最老消息 ID + created（V1）；
                // 空页（读尽）不推进——hasOlder=false 后 UI 停止触发，无死循环。
                LoadOlderSource.NETWORK -> {
                    val serverCursor = event.nextCursor
                    val id = event.oldestId
                    val created = event.oldestCreated
                    when {
                        serverCursor != null -> PaginationCursor.Network(serverCursor = serverCursor, id = id, created = created)
                        id != null && created != null -> PaginationCursor.Network(id = id, created = created)
                        else -> state.cursor
                    }
                }
            }
            state.copy(
                cursor = cursor,
                hasOlderMessages = when (event.source) {
                    LoadOlderSource.ARCHIVE -> true
                    // 2026-08-18：与 LoadNewerSucceeded 对称——服务器游标非空一定还有
                    // 更多（V2 首翻 null-cursor 场景：满页与已加载重叠但携带 cursor.next，
                    // 页大小判断之外还需游标判断，防重叠页误判读尽）
                    LoadOlderSource.NETWORK ->
                        event.nextCursor != null || event.pageSize >= event.limit
                },
                autoLoadFailures = 0,
                autoLoadPausedUntil = 0L,
                autoLoadPaused = false,
            )
        }

        is Event.LoadFailed -> {
            val failures = state.autoLoadFailures + 1
            val backoffMs = AUTO_LOAD_BACKOFF_BASE_MS *
                (1L shl (failures - 1).coerceAtMost(AUTO_LOAD_BACKOFF_MAX_SHIFT))
            state.copy(
                autoLoadFailures = failures,
                autoLoadPausedUntil = event.now + backoffMs,
                autoLoadPaused = failures >= MAX_AUTO_LOAD_FAILURES,
            )
        }

        is Event.AroundLoaded -> state.copy(
            cursor = event.olderCursor ?: PaginationCursor.HotStart,
            hasOlderMessages = event.hasOlderMessages,
            newerCursor = event.newerCursor,
            hasNewerMessages = event.hasNewerMessages,
            // 定位加载视为成功操作，重置防风暴（与 LoadSucceeded 一致）
            autoLoadFailures = 0,
            autoLoadPausedUntil = 0L,
            autoLoadPaused = false,
        )

        is Event.LoadNewerSucceeded -> {
            // newer 游标推进：与 LoadSucceeded(NETWORK) 对称。
            // V2 服务器游标优先（previousCursor）；否则回落最新消息 ID + created（V1）；
            // 空页/读尽（previousCursor 为空且不足一页）→ newerCursor=null + hasNewer=false。
            val newerCursor = when {
                event.previousCursor != null -> PaginationCursor.Network(
                    serverCursor = event.previousCursor,
                    id = event.newestId,
                    created = event.newestCreated,
                )
                event.newestId != null && event.newestCreated != null ->
                    PaginationCursor.Network(id = event.newestId, created = event.newestCreated)
                else -> null
            }
            state.copy(
                newerCursor = newerCursor,
                // 有服务器游标 → 一定还有更多更新；否则按页大小判断（满页可能还有）
                hasNewerMessages = event.previousCursor != null || event.pageSize >= event.limit,
                autoLoadFailures = 0,
                autoLoadPausedUntil = 0L,
                autoLoadPaused = false,
            )
        }
    }
}
