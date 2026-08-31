package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Security
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
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

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
 * 「新会话默认权限」设置行（DSH 专属，ServerSettingsContent 内）。
 * 只影响后续新建会话（不切当前会话）；点选即写 settings.mutate。
 */
@Composable
fun PermissionDefaultRow(
    currentValue: String? = null,
    onSelect: (String) -> Unit = {},
) {
    if (currentValue == null) return
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsListRow(
            leading = {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                    modifier = Modifier.size(20.dp),
                )
            },
            title = stringResource(R.string.server_settings_default_permission),
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
                ) {
                    Text(
                        text = presetLabel(currentValue, currentValue),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = stringResource(R.string.a11y_icon_permission_preset),
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
            PERMISSION_PRESET_VALUES.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(presetLabel(preset, preset)) },
                    onClick = {
                        expanded = false
                        onSelect(preset)
                    },
                )
            }
        }
    }
}
