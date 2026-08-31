package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.AgentPreset
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 「新会话默认 Agent 预设」设置行（DSH 专属，ServerSettingsContent 内）。
 * 只影响后续新建会话（不切当前会话）；点选即写 settings.mutate ns=agent-presets default。
 * 选项来自 roster（[presets]）；当前值按 id 解析 name 展示，未知 id 回退原串。
 */
@Composable
fun AgentPresetDefaultRow(
    presets: List<AgentPreset>,
    currentValue: String?,
    onSelect: (String) -> Unit,
) {
    if (presets.isEmpty() || currentValue == null) return
    var expanded by remember { mutableStateOf(false) }
    val currentName = presets.firstOrNull { it.id == currentValue }?.name ?: currentValue
    Box {
        SettingsListRow(
            leading = {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                    modifier = Modifier.size(20.dp),
                )
            },
            title = stringResource(R.string.server_settings_default_agent_preset),
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
                ) {
                    Text(
                        text = currentName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = stringResource(R.string.a11y_icon_agent_preset),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            presets.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset.name) },
                    onClick = {
                        expanded = false
                        onSelect(preset.id)
                    },
                )
            }
        }
    }
}
