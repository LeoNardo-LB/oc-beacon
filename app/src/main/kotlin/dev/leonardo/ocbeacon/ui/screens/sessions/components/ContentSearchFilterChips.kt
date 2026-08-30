package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.data.local.ContentSearchFilterValues
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * #272/Q6c：内容命中区过滤 chips（角色 + 时间范围，两组单选）。
 *
 * - 角色组三枚：全部（null）/ 用户（user）/ AI（assistant）
 * - 时间组三枚：全部（null）/ 近 7 天（7d）/ 近 30 天（30d）
 * - 过滤后 0 命中时该行仍保留（否则无法切回「全部」）；两行横向可滚防长文案溢出。
 * - 选中态高亮由 FilterChip 默认样式承担（与 SessionSearchBar 分类 chip 一致）。
 */
@Composable
internal fun ContentSearchFilterChips(
    role: String?,
    timeRange: String?,
    onRoleChange: (String?) -> Unit,
    onTimeRangeChange: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
        ) {
            FilterChip(
                selected = role == null,
                onClick = { onRoleChange(null) },
                label = { Text(stringResource(R.string.search_filter_role_all)) },
            )
            FilterChip(
                selected = role == ContentSearchFilterValues.ROLE_USER,
                onClick = { onRoleChange(ContentSearchFilterValues.ROLE_USER) },
                label = { Text(stringResource(R.string.search_filter_role_user)) },
            )
            FilterChip(
                selected = role == ContentSearchFilterValues.ROLE_ASSISTANT,
                onClick = { onRoleChange(ContentSearchFilterValues.ROLE_ASSISTANT) },
                label = { Text(stringResource(R.string.search_filter_role_assistant)) },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
        ) {
            FilterChip(
                selected = timeRange == null,
                onClick = { onTimeRangeChange(null) },
                label = { Text(stringResource(R.string.search_filter_time_all)) },
            )
            FilterChip(
                selected = timeRange == ContentSearchFilterValues.TIME_RANGE_7D,
                onClick = { onTimeRangeChange(ContentSearchFilterValues.TIME_RANGE_7D) },
                label = { Text(stringResource(R.string.search_filter_time_7d)) },
            )
            FilterChip(
                selected = timeRange == ContentSearchFilterValues.TIME_RANGE_30D,
                onClick = { onTimeRangeChange(ContentSearchFilterValues.TIME_RANGE_30D) },
                label = { Text(stringResource(R.string.search_filter_time_30d)) },
            )
        }
    }
}
