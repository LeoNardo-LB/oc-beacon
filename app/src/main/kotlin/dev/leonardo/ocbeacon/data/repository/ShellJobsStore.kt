package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.ShellJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 后台 shell 命令状态容器（单一真相源）。
 *
 * 维护按归属会话分组的 ShellJob 列表：
 * - SSE 事件（session.shell.started/ended）实时更新
 * - REST（GET /api/shell）拉取运行中列表作为初始快照
 *
 * 生命周期：进入会话时用 [refresh] 拉取该会话的 shell；
 * [clear] 在断开/切换服务器时调用。
 */
@Singleton
class ShellJobsStore @Inject constructor() {

    private val _jobsBySession = MutableStateFlow<Map<String, List<ShellJob>>>(emptyMap())

    /** sessionId → 该会话的后台 shell 列表（含已结束的，便于面板展示历史）。 */
    val jobsBySession: StateFlow<Map<String, List<ShellJob>>> = _jobsBySession.asStateFlow()

    /** 所有运行中的 shell（跨会话，用于全局角标计数）。 */
    val runningShells: kotlinx.coroutines.flow.Flow<List<ShellJob>> = _jobsBySession.map { all ->
        all.values.flatten().filter { it.isRunning }
    }

    /** 指定会话的 shell 列表。 */
    fun jobsFor(sessionId: String): List<ShellJob> = _jobsBySession.value[sessionId].orEmpty()

    fun onShellStarted(info: ShellJob) {
        val sid = info.sessionId ?: ""
        _jobsBySession.update { all ->
            val current = all[sid].orEmpty()
            // 同 id 去重（事件可能重放）
            if (current.any { it.id == info.id }) {
                all
            } else {
                all + (sid to (current + info))
            }
        }
    }

    fun onShellEnded(info: ShellJob, output: String?) {
        val sid = info.sessionId ?: ""
        _jobsBySession.update { all ->
            // 2026-08-12 修复（后台 shell 卡 Running 永不结束）：
            // V2 服务器 shell.exited 事件 payload 为 {id, exit, status}
            // （无 info / 无 metadata.sessionID）→ ShellJob.sessionId=null →
            // 按 "" 组更新找不到 job（started 事件带 metadata.sessionID 已正确
            // 归组）→ 状态永远卡 Running。sessionId 缺失时按 id 在所有会话组
            // 中全局查找更新。
            if (sid.isEmpty()) {
                var found = false
                val newAll = all.mapValues { (_, jobs) ->
                    jobs.map { job ->
                        if (job.id == info.id) {
                            found = true
                            job.copy(
                                status = info.status,
                                exit = info.exit ?: job.exit,
                                completedAt = info.completedAt ?: job.completedAt,
                                output = output ?: job.output
                            )
                        } else job
                    }
                }
                if (found) newAll else all
            } else {
                val current = all[sid].orEmpty()
                val updated = current.map { job ->
                    if (job.id == info.id) {
                        job.copy(
                            status = info.status,
                            exit = info.exit ?: job.exit,
                            completedAt = info.completedAt ?: job.completedAt,
                            output = output ?: job.output
                        )
                    } else job
                }
                // 事件到达时列表可能还没有该 job（如 App 中途连接）——补录
                if (updated.none { it.id == info.id }) {
                    all + (sid to (updated + info.copy(output = output ?: info.output)))
                } else {
                    all + (sid to updated)
                }
            }
        }
    }

    /** REST 快照替换：只影响运行中的列表，保留已结束的（事件补录）。 */
    fun refresh(sessionId: String, running: List<ShellJob>) {
        val sid = sessionId
        _jobsBySession.update { all ->
            val current = all[sid].orEmpty()
            // 保留当前列表中已结束的 job（REST 不返回 exited），合并运行中的快照
            val keptFinished = current.filterNot { it.isRunning }
            val merged = (running + keptFinished).distinctBy { it.id }
            all + (sid to merged)
        }
    }

    fun clear() {
        _jobsBySession.value = emptyMap()
    }

    fun clearForSession(sessionId: String) {
        _jobsBySession.update { all -> all - sessionId }
    }
}
