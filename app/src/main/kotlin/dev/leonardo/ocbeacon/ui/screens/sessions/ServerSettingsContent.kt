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
import dev.leonardo.ocbeacon.domain.model.AgentPreset
import dev.leonardo.ocbeacon.domain.model.DshAgentPresetDefault
import dev.leonardo.ocbeacon.domain.model.DshPermissionDefault
import dev.leonardo.ocbeacon.domain.model.McpServerStatus
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.ui.screens.sessions.components.AgentPresetDefaultRow
import dev.leonardo.ocbeacon.ui.screens.sessions.components.McpServerRow
import dev.leonardo.ocbeacon.ui.screens.sessions.components.PermissionDefaultRow
import dev.leonardo.ocbeacon.ui.screens.sessions.components.SettingsSectionHeader
import dev.leonardo.ocbeacon.ui.screens.sessions.components.TagManagementSection
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

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
    // DSH 新会话默认权限档（能力位门控 + 当前档 + 写回回调）
    permissionSwitchSupported: Boolean = false,
    permissionDefault: DshPermissionDefault? = null,
    onSetPermissionDefault: (String) -> Unit = {},
    // DSH 新会话默认 Agent 预设（能力位门控 + roster + 当前档 + 写回回调）
    agentPresetSupported: Boolean = false,
    agentPresets: List<AgentPreset> = emptyList(),
    agentPresetDefault: DshAgentPresetDefault? = null,
    onSetAgentPresetDefault: (String) -> Unit = {},
) {
    var mcpExpanded by remember { mutableStateOf(false) }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        // 新会话默认权限（DSH 专属，能力位门控；非 DSH 不渲染）。
        // 2026-09-01（Task 2）：行组件自身渲染 标题+分隔线+展开面板（既有区块
        // 模式，与 MCP/标签管理一致）——此处不再补独立分隔线 item。
        if (permissionSwitchSupported) {
            item {
                PermissionDefaultRow(
                    currentValue = permissionDefault?.currentValue,
                    onSelect = onSetPermissionDefault,
                    options = permissionDefault?.options.orEmpty(),
                )
            }
        }

        // 新会话默认 Agent 预设（DSH 专属，能力位门控；非 DSH 不渲染）
        if (agentPresetSupported) {
            item {
                AgentPresetDefaultRow(
                    presets = agentPresets,
                    currentValue = agentPresetDefault?.currentValue,
                    onSelect = onSetAgentPresetDefault,
                )
            }
        }

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
                                    .padding(SpacingTokens.XXL.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            }
                        }
                        mcpServers.isEmpty() -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(SpacingTokens.XXL.dp),
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
