package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.AgentPreset
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * 「新会话默认 Agent 预设」设置区块（DSH 专属，ServerSettingsContent 内）。
 * 只影响后续新建会话（不切当前会话）；点选即写 settings.mutate ns=agent-presets default。
 * 选项来自 roster（[presets]）；当前值按 id 解析 name 展示，未知 id 回退原串。
 *
 * 2026-09-01（Task 2：展开交互对齐既有模式）：由 DropdownMenu 弹层改为既有
 * 区块模式（MCP 服务器 / 标签管理同构）——SettingsSectionHeader 展示标题 +
 * 当前预设名，点击内联展开 roster 选项行（SettingsListRow + RadioButton 选中
 * 标记），点选即写；折叠态箭头 + 当前名可见。
 */
@Composable
fun AgentPresetDefaultRow(
    presets: List<AgentPreset>,
    currentValue: String?,
    onSelect: (String) -> Unit,
    /** #298：非 loopback 连接 403——区块保留但显示 loopback 标注（替代整块消失）。 */
    blocked: Boolean = false,
) {
    if (blocked) {
        DefaultsBlockedSection(stringResource(R.string.server_settings_default_agent_preset))
        return
    }
    if (presets.isEmpty() || currentValue == null) return
    var expanded by remember { mutableStateOf(false) }
    val currentName = presets.firstOrNull { it.id == currentValue }?.name ?: currentValue
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = stringResource(R.string.server_settings_default_agent_preset),
            expanded = expanded,
            onClick = { expanded = !expanded },
            trailing = {
                Text(
                    text = currentName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
        )

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                presets.forEach { preset ->
                    SettingsListRow(
                        leading = {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = if (preset.id == currentValue) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM)
                                },
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        title = preset.name,
                        trailing = {
                            RadioButton(
                                selected = preset.id == currentValue,
                                onClick = null,
                            )
                        },
                        onClick = { onSelect(preset.id) },
                    )
                }
            }
        }
    }
}
