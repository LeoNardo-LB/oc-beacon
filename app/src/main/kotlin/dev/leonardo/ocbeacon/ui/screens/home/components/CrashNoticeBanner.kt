package dev.leonardo.ocbeacon.ui.screens.home.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * #154a：崩溃启动提示横幅——上次运行存在未确认 FATAL 时显示于 Home 顶部。
 * 「查看」→ 诊断页（崩溃详情在 FATAL 条目）；「忽略」→ 确认水位推进到该崩溃
 * 时刻（后续新崩溃仍会提示）。
 */
@Composable
internal fun CrashNoticeBanner(
    crashTime: Long?,
    onView: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.BugReport,
                contentDescription = stringResource(R.string.a11y_icon_warning),
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.crash_notice_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = crashTime?.let {
                        stringResource(
                            R.string.crash_notice_body,
                            dev.leonardo.ocbeacon.util.DateFormatters.messageTimestamp(it),
                        )
                    } ?: stringResource(R.string.crash_notice_body_no_time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = AlphaTokens.HIGH),
                )
            }
            TextButton(onClick = onView) {
                Text(stringResource(R.string.crash_notice_view))
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.crash_notice_dismiss))
            }
        }
    }
}
