package dev.leonardo.ocbeacon.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import kotlinx.coroutines.delay

/**
 * 服务器不可达时显示的全屏错误 UI。
 *
 * 显示 CloudOff 图标、服务器名、带重试倒计时的状态消息，
 * 以及（倒计时归零后）重试按钮。主区域下方列出其他可用服务器，
 * 供用户切换。
 *
 * 倒计时由 [LaunchedEffect] 驱动，1 秒一次循环。
 * 当 [retryCountdown] > 0 时显示 [LinearProgressIndicator] 和倒计时文本；
 * 归零后改为显示重试 [Button]。
 *
 * @param serverName    当前（不可达）服务器的显示名称
 * @param statusMessage 连接错误的描述
 * @param retryCountdown 自动重试前剩余秒数；为 0 时显示重试按钮
 * @param otherServers  其他已知服务器列表，用户可切换过去
 * @param onRetryClick  用户点击重试按钮时调用
 * @param onSwitchServer 用户从列表选择其他服务器时调用
 * @param modifier      根布局的 Modifier
 */
@Composable
fun ConnectionErrorScreen(
    serverName: String,
    statusMessage: String,
    retryCountdown: Int,
    otherServers: List<ServerConfig>,
    onRetryClick: () -> Unit,
    onSwitchServer: (ServerConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var secondsRemaining by remember(retryCountdown) {
        mutableIntStateOf(retryCountdown)
    }

    // 倒计时循环：每秒递减，直到为 0
    LaunchedEffect(secondsRemaining) {
        if (secondsRemaining > 0) {
            delay(1000L)
            secondsRemaining--
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── 错误图标 ──
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = stringResource(R.string.connection_error_title),
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── 服务器名 ──
            Text(
                text = serverName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── 状态消息 ──
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── 倒计时进度条或重试按钮 ──
            if (secondsRemaining > 0) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(0.6f),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.connection_error_retrying_in, secondsRemaining),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = AlphaTokens.MUTED,
                    ),
                )
            } else {
                Button(onClick = onRetryClick) {
                    Text(stringResource(R.string.retry))
                }
            }

            // ── 其他服务器区 ──
            if (otherServers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(32.dp))

                HorizontalDivider()

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.connection_error_switch_server),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = AlphaTokens.MEDIUM,
                    ),
                    fontWeight = FontWeight.Medium,
                )

                Spacer(modifier = Modifier.height(8.dp))

                otherServers.forEach { server ->
                    Surface(
                        onClick = { onSwitchServer(server) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(
                            alpha = AlphaTokens.FAINT,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = server.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = server.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}
