package dev.leonardo.ocbeacon.ui.screens.chat.input

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.SessionPermissions
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * DSH 权限预设下拉选择器（输入组件第一行左对齐）。
 *
 * 按钮标签 = 当前档中文名（Full access=完全访问 / workspace write=工作区写入 /
 * read only=只读）；currentValue 不在 options 3 档内 → 显示「自定义」（灰、不可改，
 * 点击提示已自定义）。点选即回调 onSelectPreset（setPermissionPreset），回显由事件驱动。
 *
 * DSH-only 渲染由调用方按能力位 permissionSwitchSupported 门控，本组件不判服务器类型。
 */
@Composable
internal fun PermissionPresetSelector(
    permissions: SessionPermissions?,
    onSelectPreset: (String) -> Unit,
    onCustomClick: () -> Unit = {},
) {
    if (permissions == null || permissions.currentValue == null) return
    var expanded by remember { mutableStateOf(false) }
    val isCustom = permissions.isCustom
    val currentValue = permissions.currentValue ?: return
    val currentLabel = if (isCustom) {
        stringResource(R.string.permission_custom)
    } else {
        presetLabel(currentValue, currentValue)
    }
    val options = permissions.switchableOptions.ifEmpty {
        FALLBACK_PRESET_VALUES.map { SessionPermissions.PermissionPresetOption(value = it, name = it) }
    }
    // 自定义态：灰 + 不可改（点击仅提示）；常规态：点击展开菜单
    val labelColor = if (isCustom) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM)
    }
    Box {
        Row(
            modifier = Modifier
                .clip(ShapeTokens.smallMedium)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.FAINT))
                .clickable {
                    if (isCustom) onCustomClick() else expanded = true
                }
                .padding(horizontal = SpacingTokens.SM.dp, vertical = SpacingTokens.XS.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
        ) {
            Text(
                text = currentLabel,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = stringResource(R.string.a11y_icon_permission_preset),
                modifier = Modifier.size(14.dp),
                tint = labelColor,
            )
        }
        DropdownMenu(
            expanded = expanded && !isCustom,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(presetLabel(option.value, option.name)) },
                    onClick = {
                        expanded = false
                        onSelectPreset(option.value)
                    },
                )
            }
        }
    }
}

/** 档名 → 本地化产品名（3 已知档固定映射，未知档回退 name 原串——档数由部署决定）。 */
@Composable
private fun presetLabel(value: String, fallback: String): String = when (value) {
    "danger-full-access" -> stringResource(R.string.permission_full_access)
    "workspace-write" -> stringResource(R.string.permission_workspace_write)
    "read-only" -> stringResource(R.string.permission_read_only)
    else -> fallback
}

/** options 投影尚未加载（事件早于基线）时的 3 档回退（本部署固定三档）。 */
private val FALLBACK_PRESET_VALUES = listOf("read-only", "workspace-write", "danger-full-access")
