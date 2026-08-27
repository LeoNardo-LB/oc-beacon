package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
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
 * #252（2026-08-28 用户裁决：方案 b）——V2 会话级 shell 的聊天内轻提示条。
 *
 * 背景：V2 会话级 shell = 后台 shell 体系（shell.created/exited → ShellJobsStore），
 * **不产生聊天消息**（与 V1 产消息渲染轮次卡不同）——用户 `!cmd` 后聊天区原本
 * 无任何反馈。本条带展示最近一条 job（命令 + 运行中/退出码 + 输出），
 * 点击展开输出；上箭头进入既有 ShellSheet（全量历史）。
 *
 * 放置在消息列表之外（输入栏上方浮层）——刻意不进 LazyColumn：
 * #222 定音 pre-itemsIndexed 新 item 贴底时不可见且翻 isAtBottom（铁律区）。
 * 零新增翻译字符串：状态用图标 + 技术记号（exit N）表达。
 */
@Composable
fun ShellJobsStrip(
    jobs: List<ShellJob>,
    outputProvider: (ShellJob) -> String?,
    onOpenAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (jobs.isEmpty()) return
    val latest = jobs.maxByOrNull { it.startedAt ?: 0L } ?: return
    var expanded by remember(latest.id) { mutableStateOf(false) }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        tonalElevation = 2.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
        ),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "$ " + latest.command,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                ShellJobStatusIcon(latest)
                if (jobs.size > 1) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "+" + (jobs.size - 1),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onOpenAll() },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                val out = outputProvider(latest)
                when {
                    latest.isRunning -> Row(
                        modifier = Modifier.padding(top = 4.dp, start = 21.dp),
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
                            .padding(top = 4.dp, start = 21.dp)
                            .heightIn(max = 120.dp),
                    )
                    else -> {}
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
