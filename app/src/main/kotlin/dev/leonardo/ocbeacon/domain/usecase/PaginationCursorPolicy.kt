package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.util.CursorCodec
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * 分页游标策略——V1/V2 版本差异收编的单一决策点（#172）。
 *
 * 行为规格来源（迁移前就地注释链，2026-08-16 cursor-400 根治实证）：
 * - V1：本地 {id,time} before 锚点可靠（单向，无 after/cursor 能力）
 * - V2：服务器窗口语义——本地构造 cursor 仅近期窗口内 id 有效（curl 实证），
 *   增量/首次翻页**不传 cursor** 拉最新窗口 + 服务器原生 cursor.next 续页 + 空页兜底
 *
 * 版本读取被关进本模块：调用方只见能力语义，isV2 从 domain/UI 层绝迹。
 */
interface PaginationCursorPolicy {

    /**
     * 本地锚点游标（增量加载 / 归档读尽后首次网络翻页）。
     * V1: encode(id, time)；V2: null（不传 cursor 拉最新窗口，id 去重合并）。
     */
    fun localAnchorCursor(id: String?, created: Long?): String?

    /** 定位加载（快速导航）的游标对与方向能力。 */
    data class AroundCursors(
        val older: String?,
        val newer: String?,
        /** true=双向（older+newer 两请求）；false=V1 降级（仅 older 单向）。 */
        val supportsNewer: Boolean,
    )

    /** 定位加载游标对。V2: 双向 encodeV2；V1: encode(target, created) 单向。 */
    fun aroundCursors(targetId: String, targetCreated: Long): AroundCursors

    /**
     * NEWER 方向锚点（断连补漏 / 本地定位后的下滑加载更新）。
     * V2: encodeV2(anchorId, NEWER)——服务器返回该 id 之后的消息；V1: null。
     */
    fun newerAnchorCursor(anchorId: String): String?

    /** newer 方向分页能力（V2 true；V1 false——UI 依此隐藏下滑加载更新）。 */
    val supportsNewerDirection: Boolean
}

/** V1：本地 {id,time} before 锚点（实测有效）。 */
object V1CursorPolicy : PaginationCursorPolicy {
    override fun localAnchorCursor(id: String?, created: Long?): String? =
        if (id == null || created == null) null else CursorCodec.encode(id, created)

    override fun aroundCursors(targetId: String, targetCreated: Long) =
        PaginationCursorPolicy.AroundCursors(
            older = CursorCodec.encode(targetId, targetCreated),
            newer = null,
            supportsNewer = false,
        )

    override fun newerAnchorCursor(anchorId: String): String? = null

    override val supportsNewerDirection: Boolean = false
}

/** V2：服务器窗口语义（不传 cursor 拉最新 + 原生 cursor 续页；本地构造仅窗口内有效）。 */
object V2CursorPolicy : PaginationCursorPolicy {
    override fun localAnchorCursor(id: String?, created: Long?): String? = null

    override fun aroundCursors(targetId: String, targetCreated: Long) =
        PaginationCursorPolicy.AroundCursors(
            older = CursorCodec.encodeV2(targetId, CursorCodec.V2Direction.OLDER),
            newer = CursorCodec.encodeV2(targetId, CursorCodec.V2Direction.NEWER),
            supportsNewer = true,
        )

    override fun newerAnchorCursor(anchorId: String): String? =
        CursorCodec.encodeV2(anchorId, CursorCodec.V2Direction.NEWER)

    override val supportsNewerDirection: Boolean = true
}

/**
 * 策略工厂：按 serverId 读一次版本选定策略。Provider 断环（SessionStateService
 * ← SessionRepository 同款）。UNKNOWN 回退 V1 行为（探测未完成时的保守路径）。
 */
@Singleton
class PaginationCursorPolicyFactory @Inject constructor(
    private val sessionRepoProvider: Provider<SessionRepository>,
) {
    suspend fun forServer(serverId: String): PaginationCursorPolicy =
        if (sessionRepoProvider.get().getApiVersion(serverId).isV2) V2CursorPolicy
        else V1CursorPolicy
}
