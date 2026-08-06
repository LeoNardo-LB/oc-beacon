package dev.leonardo.ocbeacon.ui.screens.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.McpServerStatus
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.ui.screens.sessions.components.McpServerRow
import dev.leonardo.ocbeacon.ui.screens.sessions.components.SettingsSectionHeader
import dev.leonardo.ocbeacon.ui.screens.sessions.components.TagManagementSection
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

@Composable
fun ServerSettingsContent(
    mcpServers: List<McpServerStatus>,
    mcpLoading: String?,
    mcpInitialLoading: Boolean,
    tags: List<Tag>,
    tagAssignments: Map<String, List<String>>,
    sessions: List<Session>,
    modifier: Modifier = Modifier,
    onToggleMcp: (name: String) -> Unit = {},
    onAddTag: (Tag) -> Unit = {},
    onUpdateTag: (Tag) -> Unit = {},
    onDeleteTag: (String) -> Unit = {},
    onRemoveTagAssignment: (sessionId: String, tagId: String) -> Unit = { _, _ -> },
) {
    var mcpExpanded by remember { mutableStateOf(false) }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        // 区块标题：MCP 服务器
        item {
            SettingsSectionHeader(
                title = stringResource(R.string.mcp_servers_title),
                expanded = mcpExpanded,
                onClick = { mcpExpanded = !mcpExpanded },
            )
        }

        item {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
            )
        }

        // 可展开的 MCP 内容 — 使用 Column，不要用嵌套 LazyColumn
        item {
            AnimatedVisibility(
                visible = mcpExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    when {
                        mcpInitialLoading && mcpServers.isEmpty() -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            }
                        }
                        mcpServers.isEmpty() -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.mcp_no_servers),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        else -> {
                            mcpServers.forEach { server ->
                                McpServerRow(
                                    server = server,
                                    isLoading = mcpLoading == server.name,
                                    onToggle = { onToggleMcp(server.name) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 标签管理区块
        item {
            TagManagementSection(
                tags = tags,
                tagAssignments = tagAssignments,
                sessions = sessions,
                onAddTag = onAddTag,
                onUpdateTag = onUpdateTag,
                onDeleteTag = onDeleteTag,
                onRemoveAssignment = onRemoveTagAssignment,
            )
        }
    }
}
