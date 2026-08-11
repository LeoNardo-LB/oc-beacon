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
    }

    fun transition(state: State, event: Event): State = when (event) {
        is Event.SessionReloaded -> state.copy(
            cursor = PaginationCursor.HotStart,
            hasOlderMessages = event.hasOlderMessages,
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
                    LoadOlderSource.NETWORK -> event.pageSize >= event.limit
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
    }
}
