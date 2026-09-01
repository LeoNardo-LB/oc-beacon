package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.JobView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DSH 后台任务状态容器（单一真相源，对齐官方 jobsBySession）。
 *
 * 语义（官方 dsh-client-runtime/lib/client.js:8307-8316）：
 * - session/jobs 帧整快照 last-wins 整替换（非合并）；
 * - 空集 = 删键（该会话无后台任务）；
 * - subscribed 重连：删键清空，服务器随后重推快照。
 */
@Singleton
class DshJobsStore @Inject constructor() {

    private val _jobsBySession = MutableStateFlow<Map<String, List<JobView>>>(emptyMap())

    /** sessionId → 后台任务整快照（last-wins）。 */
    val jobsBySession: StateFlow<Map<String, List<JobView>>> = _jobsBySession.asStateFlow()

    /** 整快照 last-wins：空集删键，非空整替换。 */
    fun applySnapshot(sessionId: String, jobs: List<JobView>) {
        _jobsBySession.update { all ->
            if (jobs.isEmpty()) all - sessionId else all + (sessionId to jobs)
        }
    }

    fun clearForSession(sessionId: String) {
        _jobsBySession.update { all -> all - sessionId }
    }
}
