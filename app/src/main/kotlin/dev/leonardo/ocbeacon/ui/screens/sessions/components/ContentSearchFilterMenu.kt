package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.data.local.ContentSearchFilterValues

/**
 * #355（2026-09-10 用户裁决）：内容命中过滤改为**标准列表筛选样式**（替换原
 * tag 形式 chips——「筛选不要 tag 形式」）。形态：紧凑「筛选」入口按钮 +
 * DropdownMenu 列表（角色/时间两组单选，RadioButton 行选中态）——M3 标准
 * 列表筛选语义；当前生效过滤以纯文本摘要展示（非 tag）。
 * 选项词汇/单选语义与原 chips 完全一致（ContentSearchFilterValues 常量共源）。
 */
@Composable
internal fun ContentSearchFilterMenu(
    role: String?,
    timeRange: String?,
    onRoleChange: (String?) -> Unit,
    onTimeRangeChange: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.FilterList,
                contentDescription = null,
            )
            Text(text = stringResource(R.string.search_filter_menu))
            // 生效过滤摘要（纯文本——非 tag 形式）
            val summary = filterSummary(role, timeRange)
            if (summary != null) {
                Text(
                    text = summary,
                    modifier = Modifier.padding(start = dev.leonardo.ocbeacon.ui.theme.SpacingTokens.SM.dp), // dp via import
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FilterSection(header = stringResource(R.string.search_filter_role_section))
            FilterOption(
                label = stringResource(R.string.search_filter_role_all),
                selected = role == null,
                onClick = { onRoleChange(null); expanded = false },
            )
            FilterOption(
                label = stringResource(R.string.search_filter_role_user),
                selected = role == ContentSearchFilterValues.ROLE_USER,
                onClick = { onRoleChange(ContentSearchFilterValues.ROLE_USER); expanded = false },
            )
            FilterOption(
                label = stringResource(R.string.search_filter_role_assistant),
                selected = role == ContentSearchFilterValues.ROLE_ASSISTANT,
                onClick = { onRoleChange(ContentSearchFilterValues.ROLE_ASSISTANT); expanded = false },
            )
            FilterSection(header = stringResource(R.string.search_filter_time_section))
            FilterOption(
                label = stringResource(R.string.search_filter_time_all),
                selected = timeRange == null,
                onClick = { onTimeRangeChange(null); expanded = false },
            )
            FilterOption(
                label = stringResource(R.string.search_filter_time_7d),
                selected = timeRange == ContentSearchFilterValues.TIME_RANGE_7D,
                onClick = { onTimeRangeChange(ContentSearchFilterValues.TIME_RANGE_7D); expanded = false },
            )
            FilterOption(
                label = stringResource(R.string.search_filter_time_30d),
                selected = timeRange == ContentSearchFilterValues.TIME_RANGE_30D,
                onClick = { onTimeRangeChange(ContentSearchFilterValues.TIME_RANGE_30D); expanded = false },
            )
        }
    }
}

@Composable
private fun FilterSection(header: String) {
    Text(
        text = header,
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            horizontal = dev.leonardo.ocbeacon.ui.theme.SpacingTokens.MD.dp,
            vertical = dev.leonardo.ocbeacon.ui.theme.SpacingTokens.XS.dp,
        ), // dp via import
    )
}

@Composable
private fun FilterOption(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text = label) },
        leadingIcon = { RadioButton(selected = selected, onClick = null) },
        onClick = onClick,
    )
}

/** 生效过滤摘要（本地拼装——两组词表封闭，不新增 i18n 组合键）。 */
@Composable
private fun filterSummary(role: String?, timeRange: String?): String? {
    val roleLabel = when (role) {
        ContentSearchFilterValues.ROLE_USER -> stringResource(R.string.search_filter_role_user)
        ContentSearchFilterValues.ROLE_ASSISTANT -> stringResource(R.string.search_filter_role_assistant)
        else -> null
    }
    val timeLabel = when (timeRange) {
        ContentSearchFilterValues.TIME_RANGE_7D -> stringResource(R.string.search_filter_time_7d)
        ContentSearchFilterValues.TIME_RANGE_30D -> stringResource(R.string.search_filter_time_30d)
        else -> null
    }
    return listOfNotNull(roleLabel, timeLabel).joinToString(" · ").ifEmpty { null }
}
