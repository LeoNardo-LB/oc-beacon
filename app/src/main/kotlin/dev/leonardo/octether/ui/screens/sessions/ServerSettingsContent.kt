package dev.leonardo.octether.ui.screens.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import dev.leonardo.octether.R
import dev.leonardo.octether.domain.model.McpServerStatus
import dev.leonardo.octether.ui.screens.sessions.components.McpServerRow
import dev.leonardo.octether.ui.theme.AlphaTokens

@Composable
fun ServerSettingsContent(
    mcpServers: List<McpServerStatus>,
    mcpLoading: String?,
    mcpInitialLoading: Boolean,
    modifier: Modifier = Modifier,
    onToggleMcp: (name: String) -> Unit = {},
) {
    var mcpExpanded by remember { mutableStateOf(false) }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        // Section header: MCP Servers
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { mcpExpanded = !mcpExpanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.mcp_servers_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (mcpExpanded) Icons.Default.KeyboardArrowDown
                                  else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
            )
        }

        // Expandable MCP content — use Column, NOT nested LazyColumn
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
    }
}
