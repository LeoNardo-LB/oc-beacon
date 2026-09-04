package dev.leonardo.ocbeacon.ui.screens.chat.input

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 发送 / 停止按钮区 —— 单键统一（#326，2026-09-04 用户裁决，对齐 web 主按钮）。
 *
 * - 空闲：发送键（点击发送，长按切换 shell 模式）
 * - 忙碌+输入空白：停止键（点击中断）
 * - 忙碌+输入非空：发送键（点击=服务端排队 enqueue；**长按=直发插话 steer**
 *   #309④ 语义保留）——服务端排队 → session/queue 帧 → FAB 队列呈现
 * - 忙碌+被阻塞（等待提问/权限应答）：停止键（web blocked 同款；
 *   canSend 由 inputEnabled 关闭，输入框同步禁用）
 *
 * 取代 2026-09-01 走查 #8 的忙碌双键并存（该裁决被 #326 取代）；本地堆积
 * 链路早已随 #289 拆除。
 *
 * 可见键集与变体由 [sendStopAreaState] 纯函数决定（单测覆盖全组合）。
 *
 * @param hasText 输入框是否有非空文本
 * @param isBusy 会话是否忙碌
 * @param inputBlocked 输入被阻塞（等待提问/权限应答，inputEnabled=false）
 * @param canSend 当前是否允许发送（忙碌+文本单键态普通模式恒可=排队发送；shell+忙碌=禁用）
 * @param isSending 当前是否正在发送消息（请求在途）
 * @param isShellMode shell 模式是否激活
 * @param isAmoled AMOLED 主题是否激活
 * @param onStop 中断回调
 * @param onSend 发送回调（忙碌时语义=服务端排队）
 * @param onInputModeChange shell 模式切换回调（长按）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SendStopButton(
    hasText: Boolean,
    isBusy: Boolean = false,
    inputBlocked: Boolean = false,
    canSend: Boolean,
    isSending: Boolean,
    isShellMode: Boolean,
    isAmoled: Boolean,
    onStop: () -> Unit,
    onSend: () -> Unit,
    /** #309 批1④：忙碌长按发送键=直发插话（steer）；空闲长按仍切 shell。 */
    onSendSteer: () -> Unit = {},
    onInputModeChange: (ChatInputMode) -> Unit,
) {
    val area = sendStopAreaState(isBusy = isBusy, hasText = hasText, isSending = isSending, inputBlocked = inputBlocked)

    Row(
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (SendStopKey.STOP in area.keys) {
            StopKey(
                spinner = area.stopSpinner,
                isShellMode = isShellMode,
                isAmoled = isAmoled,
                onStop = onStop,
                // 单键时代 stopSpinner 恒 false——停止键长按切 shell 授能保留接线（历史行为）
                longPressTogglesShell = area.stopSpinner,
                onInputModeChange = onInputModeChange,
            )
        }
        if (SendStopKey.SEND in area.keys) {
            val contentDescRes = when {
                isShellMode -> R.string.chat_send_shell
                isBusy -> R.string.chat_send_queued
                else -> R.string.chat_send
            }
            SendKey(
                spinner = area.sendSpinner,
                canSend = canSend,
                isShellMode = isShellMode,
                isAmoled = isAmoled,
                contentDescRes = contentDescRes,
                onSend = onSend,
                // 忙碌长按=直发插话（steer，双键排队为主路径的定位不变）；空闲长按=shell
                longPressSteers = isBusy,
                onSteer = onSendSteer,
                onInputModeChange = onInputModeChange,
            )
        }
    }
}

/** 停止键：[spinner]=忙碌转圈变体（环形进度 + 小停止图标）；否则纯停止图标（错误色容器）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StopKey(
    spinner: Boolean,
    isShellMode: Boolean,
    isAmoled: Boolean,
    onStop: () -> Unit,
    longPressTogglesShell: Boolean,
    onInputModeChange: (ChatInputMode) -> Unit,
) {
    Box(
        modifier = Modifier
            .testTag("chat-stop")
            .size(44.dp)
            .clip(ShapeTokens.largeMedium)
            .background(
                if (spinner) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.FAINT)
                } else {
                    MaterialTheme.colorScheme.error.copy(alpha = AlphaTokens.SELECTED)
                }
            )
            .border(
                width = 1.dp,
                color = if (spinner) {
                    MaterialTheme.colorScheme.outlineVariant.copy(
                        alpha = if (isAmoled) AlphaTokens.MUTED else AlphaTokens.FAINT
                    )
                } else {
                    MaterialTheme.colorScheme.error.copy(alpha = AlphaTokens.MEDIUM)
                },
                shape = ShapeTokens.largeMedium,
            )
            .combinedClickable(
                onClick = onStop,
                onLongClick = {
                    if (longPressTogglesShell) {
                        onInputModeChange(
                            if (isShellMode) ChatInputMode.NORMAL else ChatInputMode.SHELL
                        )
                    }
                },
            ),
        contentAlignment = Alignment.Center
    ) {
        if (spinner) {
            // 2026-08-17（用户需求）：会话进行中——状态表示（环形进度）+ 停止小图标
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
        } else {
            Icon(
                Icons.Default.Stop,
                contentDescription = stringResource(R.string.chat_stop),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 发送键：[spinner]=发送中变体（环形进度 + 飞机，点击无效）；否则常规发送（忙碌时=排队）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SendKey(
    spinner: Boolean,
    canSend: Boolean,
    isShellMode: Boolean,
    isAmoled: Boolean,
    contentDescRes: Int,
    onSend: () -> Unit,
    longPressSteers: Boolean,
    onSteer: () -> Unit,
    onInputModeChange: (ChatInputMode) -> Unit,
) {
    Box(
        modifier = Modifier
            .testTag("chat-send")
            .size(44.dp)
            .clip(ShapeTokens.largeMedium)
            .background(
                if (isShellMode && !spinner) {
                    MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.FAINT)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.FAINT)
                }
            )
            .border(
                width = if (isShellMode && !spinner && isAmoled) 1.2.dp else 1.dp,
                color = when {
                    isShellMode && !spinner -> MaterialTheme.colorScheme.primary.copy(
                        alpha = if (isAmoled) AlphaTokens.AMOLED else AlphaTokens.HIGH
                    )
                    isAmoled -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MUTED)
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                },
                shape = ShapeTokens.largeMedium,
            )
            .combinedClickable(
                onClick = { if (canSend) onSend() },
                onLongClick = {
                    if (longPressSteers && canSend) {
                        onSteer()
                    } else {
                        onInputModeChange(
                            if (isShellMode) ChatInputMode.NORMAL else ChatInputMode.SHELL
                        )
                    }
                },
            ),
        contentAlignment = Alignment.Center
    ) {
        if (spinner) {
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
                    contentDescription = stringResource(contentDescRes),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(contentDescRes),
                modifier = Modifier.size(20.dp),
                tint = if (canSend) {
                    MaterialTheme.colorScheme.primary
                } else if (isShellMode && isAmoled) {
                    MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MUTED)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT)
                }
            )
        }
    }
}
