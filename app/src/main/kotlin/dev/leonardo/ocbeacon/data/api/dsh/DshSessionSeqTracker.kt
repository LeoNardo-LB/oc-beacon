package dev.leonardo.ocbeacon.data.api.dsh

import java.util.concurrent.ConcurrentHashMap

/**
 * DSH 本地已应用 seq 水位表（backlog #276 步骤⑤；设计 §1.6-5 对账协议）。
 *
 * 每 session 记录已 fold/已投递的最高 seq（DshFoldResult.lastSeq 或实况流
 * session/event 的 seq）——mux 重连后服务端重推 subscribed{lastSeq} 基线，
 * [DshReconciler.plan]（applied, baseline）比对产出回填/首拉/消失动作。
 *
 * 单调不回退：重放旧事件/乱序到达不会拉低水位（reconciler 缺口判定的前提）。
 * 进程内存表（不持久化）——冷启动全量 InitialFetch，与 V1/V2 REST 快照语义对齐。
 */
class DshSessionSeqTracker {

    private val applied = ConcurrentHashMap<String, Long>()

    /** 记录会话已应用 seq（单调 max）。 */
    fun applied(sessionId: String, seq: Long) {
        applied.merge(sessionId, seq) { old, new -> maxOf(old, new) }
    }

    fun get(sessionId: String): Long? = applied[sessionId]

    fun remove(sessionId: String) {
        applied.remove(sessionId)
    }

    fun clear() = applied.clear()

    /** 当前水位快照（脱离内部结构的拷贝——喂 DshReconciler.plan 用）。 */
    fun snapshot(): Map<String, Long> = applied.toMap()
}
