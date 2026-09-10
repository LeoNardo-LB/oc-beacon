package dev.leonardo.ocbeacon.data.api.goal

import dev.leonardo.ocbeacon.domain.model.DshGoalRef
import dev.leonardo.ocbeacon.domain.model.ServerConnection

/**
 * 目标（goal）域端口（#391 切片3）——通用命名的领域端口，端口在场即该能力可用。
 *
 * 端口命名不暗示实现来源：只有原生实现该域的服务器类型会挂载本端口；不提供的类型
 * 端口缺席（读返回空、写抛 UnsupportedServerCapability），调用方无需按类型分支。
 */
interface GoalApi {

    /** 创建并 arm 目标；回执 value.ref = 新 CAS ref。 */
    suspend fun goalCreate(
        conn: ServerConnection,
        sessionId: String,
        objective: String,
        maxGoalRounds: Long? = null,
    ): DshGoalRef?

    /** 改 objective / maxGoalRounds 之一或两者；CAS ref 取自当前投影。 */
    suspend fun goalEdit(
        conn: ServerConnection,
        sessionId: String,
        ref: DshGoalRef,
        objective: String? = null,
        maxGoalRounds: Long? = null,
    ): DshGoalRef?

    /** 暂停 active 目标并 disarm 自动延续。 */
    suspend fun goalPause(conn: ServerConnection, sessionId: String, ref: DshGoalRef): DshGoalRef?

    /** 恢复 paused 目标并重新 arm。 */
    suspend fun goalResume(conn: ServerConnection, sessionId: String, ref: DshGoalRef): DshGoalRef?

    /** 完成当前目标并 disarm。 */
    suspend fun goalComplete(conn: ServerConnection, sessionId: String, ref: DshGoalRef): DshGoalRef?

    /** 清除当前目标，保留 durable tombstone。 */
    suspend fun goalClear(conn: ServerConnection, sessionId: String, ref: DshGoalRef): Boolean
}
