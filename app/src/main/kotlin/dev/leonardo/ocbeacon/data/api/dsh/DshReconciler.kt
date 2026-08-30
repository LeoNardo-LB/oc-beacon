package dev.leonardo.ocbeacon.data.api.dsh

/**
 * DSH 断连对账状态机（backlog #275 组件 C；设计文档 §1.6-5 + §2.3）。
 *
 * DSH v1 无 since 重连游标（mux 签名存在但被忽略——重连 = 重开流 + 重拉 history，
 * §1.5 结论 1）。补偿协议：
 * 1. mux 重连后服务端自动重推全部已附会话的 session/subscribed{lastSeq} 基线
 *    （[DshSubscribed]——组件 A 解码产物）；
 * 2. 本状态机比对本地已应用 seq 表（[plan]）产出三类动作；
 * 3. 执行层（#276）按动作调 session.history{beforeSeq, maxMessages} 向前翻页回填
 *    （页边界按 append-origin 消息对齐，§1.5 结论 4），fold 后更新本地水位。
 *
 * 纯函数 / 无 IO / 无时钟——不做网络（#276 编排执行）。
 *
 * ## 边界契约（任务定稿，off-by-one 待实况验证）
 * 每会话：baseline > local + 1 → [DshReconcileAction.Backfill]；baseline ==
 * local + 1（恰落后一个 seq）或 baseline <= local → 无需动作。
 * 注意：该边界假定 lastSeq 是「订阅帧发出后新事件才从此流交付」的排他水位——
 * 若 #276 E2E 实测 lastSeq 为含入交付的闭区间水位，边界应收紧为 baseline > local
 * （本纯函数单点可调）。回填翻页方向同理：beforeSeq = baseline（向前翻页游标，
 * 含/排他语义随上同边确认）。
 */
object DshReconciler {

    /** session.history 默认页大小（任务契约：参数化，默认 50）。 */
    const val DEFAULT_PAGE_SIZE = 50

    /**
     * 生成对账计划。
     *
     * @param local 本地已应用 seq 表（sessionId → 已 fold/已投递的最高 seq；
     *   DshFoldResult.lastSeq 或实况流的水位跟踪）
     * @param baseline 订阅基线（sessionId → subscribed.lastSeq）
     * @param pageSize 回填/首拉页大小（session.history maxMessages）
     */
    fun plan(
        local: Map<String, Long>,
        baseline: Map<String, Long>,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): DshReconcilePlan {
        require(pageSize > 0) { "pageSize must be positive: " + pageSize }
        val actions = mutableListOf<DshReconcileAction>()
        for ((sessionId, baseSeq) in baseline) {
            val applied = local[sessionId]
            when {
                // 新会话（本地无水位）：首拉尾页
                applied == null -> actions += DshReconcileAction.InitialFetch(sessionId, baseSeq, pageSize)
                // 缺口（落后超过一个 seq）：向前翻页回填
                baseSeq > applied + 1 -> actions += DshReconcileAction.Backfill(sessionId, baseSeq, pageSize)
                // 相等（恰落后一）或更小（持平/超前）：无需——见类注释边界契约
            }
        }
        // 本地有、基线无：会话已消失（删除/脱离附流）——上层清理本地状态
        for (sessionId in local.keys) {
            if (sessionId !in baseline) actions += DshReconcileAction.SessionVanished(sessionId)
        }
        // map 迭代序不确定 → 按 sessionId 排序，计划可比较/可测试/日志稳定
        return DshReconcilePlan(actions.sortedBy { it.sessionId })
    }
}

/** 对账动作（#276 执行层解释）。 */
sealed class DshReconcileAction {
    abstract val sessionId: String

    /**
     * 缺口回填：session.history{beforeSeq, maxMessages} 向前翻页，fold 后推进本地
     * 水位；仍有缺口则以上一页最小 seq 继续翻页（编排层循环）。
     */
    data class Backfill(
        override val sessionId: String,
        val beforeSeq: Long,
        val maxMessages: Int,
    ) : DshReconcileAction()

    /** 新会话首拉：同 Backfill 载荷，语义区分日志/遥测。 */
    data class InitialFetch(
        override val sessionId: String,
        val beforeSeq: Long,
        val maxMessages: Int,
    ) : DshReconcileAction()

    /** 会话消失：不在新基线中——上层清理消息/部件/未读等本地状态。 */
    data class SessionVanished(override val sessionId: String) : DshReconcileAction()
}

/** 对账计划：按 sessionId 排序的动作列表（空 = 完全同步，无需任何 IO）。 */
data class DshReconcilePlan(val actions: List<DshReconcileAction>) {
    val isFullySynced: Boolean get() = actions.isEmpty()
}
