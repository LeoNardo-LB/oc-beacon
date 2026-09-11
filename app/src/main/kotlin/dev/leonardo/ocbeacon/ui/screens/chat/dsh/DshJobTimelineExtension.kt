package dev.leonardo.ocbeacon.ui.screens.chat.dsh

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.JobStatus
import dev.leonardo.ocbeacon.domain.model.ServerCapabilities
import dev.leonardo.ocbeacon.domain.model.ServerFeatures
import dev.leonardo.ocbeacon.domain.model.ServerUiSlot
import dev.leonardo.ocbeacon.ui.extension.ChatMessageListSlotHost
import dev.leonardo.ocbeacon.ui.extension.ServerUiExtension
import dev.leonardo.ocbeacon.ui.extension.ServerUiSlotHost
import dev.leonardo.ocbeacon.ui.screens.chat.components.EventCard
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DSH 后台任务降级 Shell 卡（#399：从通用 ChatMessageList 的私有 DshJobTimelineCard 迁入
 * 类型私有包，经 CHAT_MESSAGE_LIST 插槽渲染）。
 *
 * 数据源 session/jobs 整快照（JobView）→ 统一事件卡 EventCard 视觉；kind/label/status/detail
 * 先行，command/output 留空（DSH 无命令输出源，如实降级）；失败/被杀破色（与既有 shell/task 卡同）。
 *
 * 启用条件 = JOBS_PUSH 能力位（DSH 私有帧推送能力），不读服务器类型；通用壳不 import 本类。
 */
@Singleton
class DshJobTimelineExtension @Inject constructor() : ServerUiExtension {

    override val slot: ServerUiSlot = ServerUiSlot.CHAT_MESSAGE_LIST

    override val order: Int = 100

    override fun isEnabled(caps: ServerCapabilities): Boolean =
        ServerFeatures.JOBS_PUSH in caps

    @Composable
    override fun Content(host: ServerUiSlotHost) {
        require(host is ChatMessageListSlotHost) {
            "CHAT_MESSAGE_LIST 槽位收到不匹配的宿主: " + host::class.simpleName
        }
        val job = host.job
        val failed = job.statusKind == JobStatus.FAILED || job.statusKind == JobStatus.KILLED
        EventCard(
            eventKey = "dsh_job_" + job.id,
            timeMs = job.startedAt,
            label = dshJobStatusLabel(job.statusKind),
            leadingIcon = if (job.kind == "subagent") Icons.Filled.AccountTree else Icons.Filled.Terminal,
            failed = failed,
            // 描述行：kind · label · detail（数据在才显示；无命令/输出）
            description = listOfNotNull(
                job.kind.takeIf { it.isNotBlank() },
                job.label.takeIf { it.isNotBlank() },
                job.detail?.takeIf { it.isNotBlank() },
            ).joinToString(" · ").takeIf { it.isNotBlank() },
            expandedStates = host.expandedStates,
        )
    }
}

/** 任务状态 → 既有 dsh_job_status_* 文案（Task 3d 降级 Shell 卡；零新增 i18n；
 *  #284：statusKind 枚举分支，UNKNOWN 沿用 completed 兜底保持原渲染）。 */
@Composable
private fun dshJobStatusLabel(status: JobStatus): String = stringResource(
    when (status) {
        JobStatus.RUNNING -> R.string.dsh_job_status_running
        JobStatus.STOPPING -> R.string.dsh_job_status_stopping
        JobStatus.KILLED -> R.string.dsh_job_status_killed
        JobStatus.FAILED -> R.string.dsh_job_status_failed
        JobStatus.COMPLETED,
        JobStatus.UNKNOWN -> R.string.dsh_job_status_completed
    }
)
