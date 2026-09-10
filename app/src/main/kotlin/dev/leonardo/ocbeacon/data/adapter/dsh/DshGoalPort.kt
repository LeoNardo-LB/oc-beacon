package dev.leonardo.ocbeacon.data.adapter.dsh

import dev.leonardo.ocbeacon.data.api.dsh.DshApiClient
import dev.leonardo.ocbeacon.data.api.goal.GoalApi
import dev.leonardo.ocbeacon.domain.model.DshGoalRef
import dev.leonardo.ocbeacon.domain.model.ServerConnection

/**
 * DSH 目标域端口实现（#391 切片3）——薄委托到协议客户端，端口与协议实现解耦。
 */
class DshGoalPort(
    private val dsh: DshApiClient,
) : GoalApi {

    override suspend fun goalCreate(
        conn: ServerConnection,
        sessionId: String,
        objective: String,
        maxGoalRounds: Long?,
    ): DshGoalRef? = dsh.goalCreate(conn, sessionId, objective, maxGoalRounds)

    override suspend fun goalEdit(
        conn: ServerConnection,
        sessionId: String,
        ref: DshGoalRef,
        objective: String?,
        maxGoalRounds: Long?,
    ): DshGoalRef? = dsh.goalEdit(conn, sessionId, ref, objective, maxGoalRounds)

    override suspend fun goalPause(conn: ServerConnection, sessionId: String, ref: DshGoalRef): DshGoalRef? =
        dsh.goalPause(conn, sessionId, ref)

    override suspend fun goalResume(conn: ServerConnection, sessionId: String, ref: DshGoalRef): DshGoalRef? =
        dsh.goalResume(conn, sessionId, ref)

    override suspend fun goalComplete(conn: ServerConnection, sessionId: String, ref: DshGoalRef): DshGoalRef? =
        dsh.goalComplete(conn, sessionId, ref)

    override suspend fun goalClear(conn: ServerConnection, sessionId: String, ref: DshGoalRef): Boolean =
        dsh.goalClear(conn, sessionId, ref)
}
