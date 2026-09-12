package dev.leonardo.ocbeacon.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import kotlinx.coroutines.delay

/**
 * #267（spec §3.2）：服务器断连常驻细条幅。
 *
 * - 非 Connected（含重连退避期）时由调用方条件渲染；恢复 Connected 直接消失
 *   （不弹「已恢复」提示——Q12a 裁决）；
 * - 细条幅形态：errorContainer 底 + CloudOff 图标 + 单行文案，贴 TopAppBar 下沿；
 * - 零交互（点击不重连——重连循环常驻自动进行，无需手动触发）。
 * - #409（2026-09-12 用户反馈）：[retryAtEpochMs] 非空（= 已排定下次自动重连时间）时，
 *   文案追加「N 秒后重试」倒计时。
 */
@Composable
fun ServerLinkBanner(modifier: Modifier = Modifier, retryAtEpochMs: Long? = null) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        // 同 DshTokenNeededBanner：topBar Column 无状态栏避让——顶部沉入被裁（#267
        // 零交互横幅无触摸损失，仅视觉顶部被状态栏盖住 ~105px；统一避让）。
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.XS.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = if (retryAtEpochMs != null) {
                    stringResource(
                        R.string.server_link_disconnected_banner_countdown,
                        rememberRetrySeconds(retryAtEpochMs),
                    )
                } else {
                    stringResource(R.string.server_link_disconnected_banner)
                },
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp),
            )
        }
    }
}

/**
 * #408（2026-09-12 用户反馈）：topBar 上方渲染了自带 statusBars inset 的横幅
 * （[ServerLinkBanner] / DshTokenNeededBanner）时，其下方 TopAppBar 必须改用「零 inset」。
 *
 * 根因：M3 TopAppBar 默认吃一次状态栏 inset；横幅自己也吃一次 statusBars padding。
 * 状态栏高度被计两次 → 内容被顶推「横幅高度 + 2×状态栏」（本机实测 195px，应有 66px）。
 * 横幅在上、TopAppBar 在下时，状态栏归属权只应属于最上面的那个。
 */
val ZeroTopAppBarWindowInsets: WindowInsets = WindowInsets(0, 0, 0, 0)

/** #409：每秒重算距下次重试的剩余秒数（向上取整，最小 0）。 */
@Composable
private fun rememberRetrySeconds(retryAtEpochMs: Long): Int {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(retryAtEpochMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return ((retryAtEpochMs - nowMs + 999) / 1_000).coerceAtLeast(0).toInt()
}