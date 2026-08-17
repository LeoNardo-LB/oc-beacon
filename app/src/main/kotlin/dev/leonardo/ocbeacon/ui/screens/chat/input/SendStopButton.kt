package dev.leonardo.ocbeacon.ui.screens.chat.input

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens

/**
 * 发送 / 停止按钮 —— 点击发送或停止，长按切换 shell 模式。
 *
 * @param showStop 是否应显示停止图标（忙碌且无文本）
 * @param canSend 当前是否允许发送
 * @param isSending 当前是否正在发送消息
 * @param isShellMode shell 模式是否激活
 * @param isAmoled AMOLED 主题是否激活
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SendStopButton(
    showStop: Boolean,
    /** 2026-08-17（用户需求）：会话进行中——状态表示（转圈）放发送按钮上，点击中断。 */
    isBusy: Boolean = false,
    canSend: Boolean,
    isSending: Boolean,
    isShellMode: Boolean,
    isAmoled: Boolean,
    onStop: () -> Unit,
    onSend: () -> Unit,
    onInputModeChange: (ChatInputMode) -> Unit
) {
    // busy 且有输入（showStop=false）时：转圈样式 + 点击中断
    //（#129 方案 C 语义迁移：等不及僵尸兜底时手动解除）
    val busySpinner = isBusy && !showStop && !isSending
    Box(
        modifier = Modifier
            .testTag("chat-send")
            .size(44.dp)
            .clip(ShapeTokens.largeMedium)
            .background(
                if (showStop) {
                    MaterialTheme.colorScheme.error.copy(alpha = AlphaTokens.SELECTED)
                } else if (isShellMode && !isSending) {
                    MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.FAINT)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.FAINT)
                }
            )
            .then(
                if (showStop) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.error.copy(alpha = AlphaTokens.MEDIUM),
                        shape = ShapeTokens.largeMedium,
                    )
                } else if (isShellMode && !isSending) {
                    Modifier.border(
                        width = if (isAmoled) 1.2.dp else 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = if (isAmoled) AlphaTokens.AMOLED else AlphaTokens.HIGH),
                        shape = ShapeTokens.largeMedium,
                    )
                } else if (isAmoled) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MUTED),
                        shape = ShapeTokens.largeMedium,
                    )
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
                        shape = ShapeTokens.largeMedium,
                    )
                }
            )
            .combinedClickable(
                onClick = {
                    if (showStop) {
                        onStop()
                    } else if (busySpinner) {
                        // 2026-08-17：busy 中点击 = 中断（原第一行转圈的可点击语义）
                        onStop()
                    } else if (canSend) {
                        onSend()
                    }
                },
                onLongClick = {
                    if (!showStop) {
                        onInputModeChange(
                            if (isShellMode) ChatInputMode.NORMAL else ChatInputMode.SHELL
                        )
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (showStop) {
            Icon(
                Icons.Default.Stop,
                contentDescription = stringResource(R.string.chat_stop),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error
            )
        } else if (busySpinner) {
            // 2026-08-17（用户需求）：会话进行中——状态表示（环形进度）+ 停止小图标，
            // 点击中断（原第一行转圈样式合并至此）
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.FAINT)
                )
                Icon(
                    Icons.Default.Stop,
                    contentDescription = stringResource(R.string.chat_stop),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (isSending) {
            // 2026-08-11 用户要求：外壁大小不变，飞机图标保留，
            // loading 动效附着内壁（环形进度圈绕图标一圈）。
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.FAINT)
                )
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.chat_send),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = if (isShellMode) {
                    stringResource(R.string.chat_send_shell)
                } else {
                    stringResource(R.string.chat_send)
                },
                modifier = Modifier.size(20.dp),
                tint = if (canSend) {
                    MaterialTheme.colorScheme.primary
                } else if (isShellMode && isAmoled && !isSending) {
                    MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MUTED)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT)
                }
            )
        }
    }
}
