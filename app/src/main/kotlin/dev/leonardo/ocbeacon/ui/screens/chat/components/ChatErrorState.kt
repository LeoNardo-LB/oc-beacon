package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import kotlinx.coroutines.delay

private const val INITIAL_RETRY_SECONDS = 5
private const val MAX_RETRY_SECONDS = 60

/**
 * Error state shown when loading fails and there are no messages to display.
 * Auto-retries with exponential backoff: 5s → 10s → 20s → 40s → 60s (capped).
 * #134（D2-L47）：原固定 5s 自动重试——服务器不可达时无限高频请求；
 * 连续失败间隔翻倍，成功加载（本组件退出组合）后重置。
 */
@Composable
fun ChatErrorState(
    modifier: Modifier = Modifier,
    error: String?,
    onRetry: () -> Unit
) {
    // 退避状态跨 error 变化保留（连续失败递增）；组件退出组合（加载成功）后自然重置
    var retryDelaySec by remember { mutableIntStateOf(INITIAL_RETRY_SECONDS) }
    var countdown by remember(error) { mutableIntStateOf(INITIAL_RETRY_SECONDS) }

    LaunchedEffect(error) {
        countdown = retryDelaySec
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
        onRetry()
        // 下一次自动重试间隔指数退避（封顶）
        retryDelaySec = (retryDelaySec * 2).coerceAtMost(MAX_RETRY_SECONDS)
    }

    Column(
        modifier = modifier
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = stringResource(R.string.a11y_icon_warning),
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error
        )
        ErrorPayloadContent(
            text = error ?: stringResource(R.string.session_unknown_error),
            textStyle = MaterialTheme.typography.bodyLarge,
            textColor = MaterialTheme.colorScheme.error,
        )
        Button(onClick = onRetry) {
            Text(stringResource(R.string.retry) + if (countdown > 0) " ($countdown)" else "")
        }
    }
}
