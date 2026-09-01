package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * DSH 后台任务视图（session/jobs 帧整快照项）。
 *
 * wire 形状对齐 `taskViewSchema`（dsh-client-connection/lib/client.js:5477-5487；
 * jobs.d.ts:15-34）：
 * ```
 * {id, kind, label, status: running|stopping|completed|killed|failed,
 *  detail?, startedAt, finishedAt?}
 * ```
 *
 * - [kind]：`bash` | `subagent`（wire min-length-1 开放串，UI 按原样徽章展示）；
 * - [status]：五值闭集，无 `stopped`（stopping 是瞬时态）；
 * - [finishedAt]：终态才有；运行中缺席。
 *
 * 语义：整快照 last-wins（空集删键、subscribed 清空重推——对齐官方
 * dsh-client-runtime/lib/client.js:8307-8316）。
 */
@Serializable
data class JobView(
    val id: String,
    val kind: String,
    val label: String,
    val status: String,
    val detail: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
) {
    val isRunning: Boolean get() = status == STATUS_RUNNING

    /** #284：语义化状态枚举（wire 字符串保持不变——服务器权威值直存）。 */
    val statusKind: JobStatus get() = JobStatus.fromWire(status)

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_STOPPING = "stopping"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_KILLED = "killed"
        const val STATUS_FAILED = "failed"
    }
}

/** #284：JobView.status 语义枚举（unknown = 服务器新增枚举，渲染走默认分支）。 */
@Serializable
enum class JobStatus {
    RUNNING, STOPPING, COMPLETED, KILLED, FAILED, UNKNOWN;

    val isTerminal: Boolean get() = this == COMPLETED || this == KILLED || this == FAILED
    val isActive: Boolean get() = this == RUNNING || this == STOPPING

    companion object {
        fun fromWire(raw: String): JobStatus = when (raw) {
            JobView.STATUS_RUNNING -> RUNNING
            JobView.STATUS_STOPPING -> STOPPING
            JobView.STATUS_COMPLETED -> COMPLETED
            JobView.STATUS_KILLED -> KILLED
            JobView.STATUS_FAILED -> FAILED
            else -> UNKNOWN
        }
    }
}
