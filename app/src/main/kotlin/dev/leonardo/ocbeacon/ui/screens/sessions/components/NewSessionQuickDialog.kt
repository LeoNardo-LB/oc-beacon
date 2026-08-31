package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

internal data class RecentSessionDirectory(
    val directory: String,
    val name: String,
    val count: Int,
    val lastUsed: Long,
)

/**
 * 将 [sessions] 按目录分组并返回最近使用的目录，最多 [limit] 个。
 * 用于填充快速新建会话对话框。
 */
internal fun recentSessionDirectories(
    sessions: List<Session>,
    limit: Int = 20,
): List<RecentSessionDirectory> = sessions
    // 防御：V2 服务器存在 location.directory 为 "/" 的会话（实测 ses_005890631ffe...），
    // "/" 经 trimEnd('/') 后为空 → 分组 key 空 → 产生"空目录"条目（2026-08-13 用户反馈）。
    // 根目录无法作为新建会话目标，过滤（按 trim 后判断，覆盖 "" 与 "/" 两种形态）
    .filter { it.directory.replace('\\', '/').trimEnd('/').isNotBlank() }
    .groupBy { it.directory.replace('\\', '/').trimEnd('/') }
    .map { (directory, items) ->
        RecentSessionDirectory(
            directory = items.first().directory,
            name = directory.substringAfterLast('/').ifEmpty { directory },
            count = items.size,
            lastUsed = items.maxOf { it.time.updated },
        )
    }
    // lastUsed 并列时按 directory 字典序消解：live 流以不同顺序重发同一批会话时
    // 行序保持确定，避免对话框已显示的行发生漂移（点选落位错行的成因之一）。
    .sortedWith(
        compareByDescending(RecentSessionDirectory::lastUsed)
            .thenBy(RecentSessionDirectory::directory),
    )
    .take(limit)

/**
 * 创建新会话的快速启动对话框。
 * 显示从已有会话聚合出的唯一项目目录，按最近使用排序 —
 * 一键即可在那里启动会话。底部的"浏览…"行打开完整目录选择器（[onBrowse]）。
 *
 * 移植自上游 oc-remote v1.7.0，适配 AMOLED 对话框令牌系统。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewSessionQuickDialog(
    sessions: List<Session>,
    limit: Int,
    onSelectDirectory: (String) -> Unit,
    onBrowse: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 行序快照：仅在本对话框进入组合（打开）那一刻计算一次 —— 对话框存活期内
    // live 流重发（后台刷新 / 异步加载完成的重排窗口）不再改动已显示的行序，
    // 消除点选时目标行漂移导致的落位错行（DSH E2E 发现，人类用户同样可命中邻行）。
    // 关闭后重新打开会重新组合，自然取到最新列表。手验：打开对话框 → 后台触发
    // 列表变化（如另一端新建会话）→ 行序应保持不变。
    val dirEntries = remember(limit) { recentSessionDirectories(sessions, limit) }
    val params = amoledDialogParams(shape = ShapeTokens.largeMedium)

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = params.shape,
            color = params.containerColor,
            border = params.border,
            tonalElevation = params.tonalElevation,
        ) {
            Column(modifier = Modifier.padding(vertical = SpacingTokens.LG.dp)) {
                Text(
                    text = stringResource(R.string.sessions_new_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = SpacingTokens.MD.dp),
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    items(dirEntries, key = { it.directory }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectDirectory(entry.directory) }
                                .padding(horizontal = 20.dp, vertical = SpacingTokens.MD.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.MD.dp),
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.HIGH),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = entry.directory.trimEnd('/'),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = "${entry.count}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                            )
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = SpacingTokens.XS.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onBrowse() }
                        .padding(horizontal = 20.dp, vertical = SpacingTokens.MD.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.MD.dp),
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                    )
                    Text(
                        text = stringResource(R.string.sessions_open_other_project),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
