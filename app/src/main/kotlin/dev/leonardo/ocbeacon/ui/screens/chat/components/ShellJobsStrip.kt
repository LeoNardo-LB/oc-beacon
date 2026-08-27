package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.domain.model.ShellJob

/**
 * #252（2026-08-28 用户裁决：方案 b + TUI 语义修正）——V2 会话级 shell 的
 * **对话流内嵌卡**：命令与输出长在消息流里（贴最新消息下方，随列表滚动），
 * 与 opencode TUI 的 `!cmd` 行为一致；非浮层。
 *
 * 背景：V2 会话级 shell = 后台 shell 体系（shell.created/exited → ShellJobsStore），
 * 不产生聊天消息（与 V1 产消息渲染轮次卡不同）。数据源 ShellJobsStore（SSE 实时 +
 * REST 运行中快照），输出经三级 provider（事件输出 → 消息流回填 → REST 拉取）。
 * 时序旧 → 新；最新一条默认展开输出，历史行点击展开。
 */
@Composable
fun ShellJobsTranscriptCard(
    jobs: List<ShellJob>,
    outputProvider: (ShellJob) -> String?,
) {
    if (jobs.isEmpty()) return
    val ordered = remember(jobs) { jobs.sortedBy { it.startedAt ?: 0L } }
    val latestId = ordered.lastOrNull()?.id
    var expandedIds by remember { mutableStateOf(setOf<String>()) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            ordered.forEachIndexed { index, job ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 3.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    )
                }
                val open = expandedIds.contains(job.id) || job.id == latestId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expandedIds = if (open && job.id == latestId) {
                                expandedIds - job.id
                            } else {
                                expandedIds + job.id
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$ " + job.command,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    ShellJobStatusIcon(job)
                    if (job.id != latestId) {
                        Icon(
                            if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (open) {
                    val out = outputProvider(job)
                    when {
                        job.isRunning -> Row(
                            modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        out != null && out.isNotBlank() -> Text(
                            text = out.takeLast(1500),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(start = 20.dp, top = 2.dp, bottom = 4.dp)
                                .heightIn(max = 140.dp),
                        )
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun ShellJobStatusIcon(job: ShellJob) {
    when {
        job.isRunning -> CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
        (job.exit ?: 0) == 0 -> Icon(
            Icons.Default.Check,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = "exit " + job.exit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}