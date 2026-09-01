package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
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
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/** 部署固定三档（本部署 settings enum；档数由部署决定，此处仅作默认档选择器回退）。 */
private val PERMISSION_PRESET_VALUES = listOf("read-only", "workspace-write", "danger-full-access")

/** 档名 → 本地化产品名（3 已知档固定映射，未知档回退原串）。 */
@Composable
private fun presetLabel(value: String, fallback: String): String = when (value) {
    "danger-full-access" -> stringResource(R.string.permission_full_access)
    "workspace-write" -> stringResource(R.string.permission_workspace_write)
    "read-only" -> stringResource(R.string.permission_read_only)
    else -> fallback
}

/**
 * 「新会话默认权限」设置区块（DSH 专属，ServerSettingsContent 内）。
 * 只影响后续新建会话（不切当前会话）；点选即写 settings.mutate。
 *
 * 2026-09-01（Task 2：展开交互对齐既有模式）：由 DropdownMenu 弹层改为既有
 * 区块模式（MCP 服务器 / 标签管理同构）——SettingsSectionHeader 展示标题 +
 * 当前档，点击内联展开选项行（SettingsListRow + Material3 RadioButton 选中
 * 标记），点选即写；折叠态箭头 + 当前档名可见。
 */
@Composable
fun PermissionDefaultRow(
    currentValue: String? = null,
    onSelect: (String) -> Unit = {},
    /** #283：settings.describe schema enum 动态档集（空 = 回退已知三档）。 */
    options: List<String> = emptyList(),
) {
    if (currentValue == null) return
    val presets = options.ifEmpty { PERMISSION_PRESET_VALUES }
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        // 区块标题（可点击展开/收起，样式同 MCP/标签区块；trailing 常驻当前档名）
        SettingsSectionHeader(
            title = stringResource(R.string.server_settings_default_permission),
            expanded = expanded,
            onClick = { expanded = !expanded },
            trailing = {
                Text(
                    text = presetLabel(currentValue, currentValue),
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
                                Icons.Default.Security,
                                contentDescription = null,
                                tint = if (preset == currentValue) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM)
                                },
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        title = presetLabel(preset, preset),
                        trailing = {
                            RadioButton(
                                selected = preset == currentValue,
                                onClick = null,
                            )
                        },
                        onClick = { onSelect(preset) },
                    )
                }
            }
        }
    }
}
