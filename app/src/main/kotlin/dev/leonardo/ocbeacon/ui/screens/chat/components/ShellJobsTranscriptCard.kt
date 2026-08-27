package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import dev.leonardo.ocbeacon.domain.model.ShellJob
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.ShellCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * #252（2026-08-28 用户裁决：方案 b + TUI 语义 + 视觉统一）——V2 会话级 shell
 * 的**对话流内嵌卡**：每个 job 渲染一张 [ShellCard]，与消息流内的工具卡片
 * （$ echo 卡）同款视觉体系（ToolCardScaffold），协调性由构造保证。
 *
 * 背景：V2 会话级 shell = 后台 shell 体系（shell.created/exited → ShellJobsStore），
 * 不产生聊天消息（与 V1 产消息渲染轮次卡不同）。数据源 ShellJobsStore（SSE 实时 +
 * REST 运行中快照），输出经三级 provider（事件输出 → 消息流回填 → REST 拉取）。
 * 时序旧 → 新；最新一条默认展开输出（TUI 行为），历史行点击切换展开。
 */
@Composable
fun ShellJobsTranscriptCard(
    jobs: List<ShellJob>,
    outputProvider: (ShellJob) -> String?,
) {
    if (jobs.isEmpty()) return
    val ordered = remember(jobs) { jobs.sortedBy { it.startedAt ?: 0L } }
    val latestId = ordered.lastOrNull()?.id
    var manualExpanded by remember { mutableStateOf(setOf<String>()) }
    Column {
        ordered.forEachIndexed { index, job ->
            if (index > 0) Spacer(Modifier.height(4.dp))
            val isExpanded = manualExpanded.contains(job.id) || job.id == latestId
            ShellCard(
                shell = Part.Shell(
                    id = job.id,
                    shellId = job.id,
                    command = job.command,
                    status = job.status,
                    exit = job.exit,
                    output = job.output ?: outputProvider(job),
                ),
                isExpanded = isExpanded,
                onToggleExpand = {
                    manualExpanded = if (isExpanded) {
                        manualExpanded - job.id
                    } else {
                        manualExpanded + job.id
                    }
                },
            )
        }
    }
}