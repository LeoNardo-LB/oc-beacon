package dev.leonardo.ocbeacon.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.screens.settings.SettingsViewModel
import dev.leonardo.ocbeacon.ui.screens.settings.components.SectionHeader
import dev.leonardo.ocbeacon.ui.theme.ListItemTokens

/**
 * #271 字节人性化格式化（存储统计）：B → KB → MB → GB。
 * 精度对齐聊天附件 formatFileSize（KB 一位小数、MB/GB 两位小数）。
 */
internal fun formatArchiveBytes(bytes: Long): String {
    val value = bytes.toDouble()
    return when {
        value >= 1_073_741_824.0 -> String.format("%.2f GB", value / 1_073_741_824.0)
        value >= 1_048_576.0 -> String.format("%.2f MB", value / 1_048_576.0)
        value >= 1024.0 -> String.format("%.1f KB", value / 1024.0)
        else -> "$bytes B"
    }
}

/**
 * #271 设置页「存储占用」区：冷存桶全库统计（桶数/消息条数/压缩字节）+
 * 手动清理入口（清理行的二次确认对话框由 SettingsScreen 承载）。
 */
@Composable
fun StorageSection(
    viewModel: SettingsViewModel,
    onShowClearConfirmDialog: () -> Unit,
) {
    val archiveStats by viewModel.archiveStats.collectAsStateWithLifecycle()
    // 委托属性不可 smart cast，先落局部 val
    val stats = archiveStats

    SectionHeader(stringResource(R.string.settings_storage_section))

    // 统计卡：标题 + 描述 + 三行统计（空态/加载态降级）
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_storage_archive_title)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.settings_storage_archive_desc))
                when {
                    // 加载中：先只显示描述，避免闪现「暂无归档」空态
                    stats == null -> Unit
                    stats.bucketCount == 0L -> Text(stringResource(R.string.settings_storage_empty))
                    else -> {
                        Text(stringResource(R.string.settings_storage_stat_buckets, stats.bucketCount))
                        Text(stringResource(R.string.settings_storage_stat_messages, stats.messageCount))
                        Text(stringResource(R.string.settings_storage_stat_bytes, formatArchiveBytes(stats.bytes)))
                    }
                }
            }
        },
        leadingContent = {
            Icon(Icons.Default.Archive, contentDescription = null)
        },
        modifier = Modifier.padding(ListItemTokens.ContentPaddingMedium)
    )

    // 手动清理入口：空态时无物可清，隐藏
    if (stats != null && stats.bucketCount > 0L) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_storage_clear)) },
            leadingContent = {
                Icon(Icons.Default.DeleteSweep, contentDescription = null)
            },
            modifier = Modifier
                .clickable { onShowClearConfirmDialog() }
                .padding(ListItemTokens.ContentPaddingMedium)
        )
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}
