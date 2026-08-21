package dev.leonardo.ocbeacon.ui.screens.chat.input

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 发送 / 停止按钮 —— 点击发送或停止，长按切换 shell 模式。
 *
 * busy 且输入框有内容时（转圈态）：普通聊天模式弹出气泡菜单（2026-08-20
 * 设计定稿，两项：立即发送=服务端排队不中断；堆积消息=本地暂存 turn 结束
 * 后自动发）；shell 模式维持点击中断；[onEnqueue] 为 null 的调用方维持
 * 旧的点击中断行为。
 *
 * @param showStop 是否应显示停止图标（忙碌且无文本）
 * @param canSend 当前是否允许发送
 * @param isSending 当前是否正在发送消息
 * @param isShellMode shell 模式是否激活
 * @param isAmoled AMOLED 主题是否激活
 * @param hasAttachments 输入框是否带图片附件（堆积仅支持纯文本，置灰提示）
 * @param onEnqueue 堆积消息回调（null = 不支持堆积，转圈点击维持中断）
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
    hasAttachments: Boolean = false,
    onStop: () -> Unit,
    onSend: () -> Unit,
    onInputModeChange: (ChatInputMode) -> Unit,
    onEnqueue: (() -> Unit)? = null,
) {
    // busy 且有输入（showStop=false）时：转圈样式 + 气泡菜单（或中断）
    //（#129 方案 C 语义迁移 + 2026-08-20 气泡化）
    val busySpinner = isBusy && !showStop && !isSending
    var showBusyMenu by remember { mutableStateOf(false) }
    // 气泡实测高度（onSizeChanged 回填）——用于向上偏移定位
    var menuHeightPx by remember { mutableIntStateOf(0) }

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
                        // 2026-08-20 设计定稿：busy+有内容——普通模式弹气泡
                        //（立即发送 / 堆积消息）；shell 模式与不支持堆积的调用方维持中断
                        if (!isShellMode && onEnqueue != null) {
                            showBusyMenu = true
                        } else {
                            onStop()
                        }
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

        // #178：busy 气泡不再抢窗级焦点（focusable=false），软键盘保持拉起；
        // 代价是返回键不再由 Popup 窗口消化——BackHandler 只关气泡不动键盘。
        BackHandler(enabled = showBusyMenu && busySpinner) { showBusyMenu = false }

        // busy 气泡菜单（锚定按钮上方右对齐；点外部/BackHandler 关闭不做事）
        if (showBusyMenu && busySpinner) {
            val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, -(menuHeightPx + gapPx)),
                onDismissRequest = { showBusyMenu = false },
                // #178 根因修复：focusable=true 抢占窗级焦点 → IME 被收起。
                // 改 false 后键盘保持；返回键语义由上方 BackHandler 接管。
                properties = PopupProperties(focusable = false),
            ) {
                Surface(
                    modifier = Modifier
                        .testTag("chat-busy-menu")
                        .onSizeChanged { menuHeightPx = it.height },
                    shape = ShapeTokens.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
                ) {
                    Column(modifier = Modifier.padding(vertical = SpacingTokens.XS.dp)) {
                        // 两项：立即发送（mode 2 恢复）/ 堆积消息（mode 3）
                        BusyMenuItem(
                            icon = { tint ->
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = tint
                                )
                            },
                            title = stringResource(R.string.chat_busy_menu_send_now),
                            subtitle = stringResource(R.string.chat_busy_menu_send_now_desc),
                            enabled = true,
                            onClick = {
                                showBusyMenu = false
                                onSend()
                            }
                        )
                        BusyMenuItem(
                            icon = { tint ->
                                Icon(
                                    Icons.Filled.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = tint
                                )
                            },
                            title = stringResource(R.string.chat_busy_menu_stack),
                            subtitle = stringResource(
                                if (hasAttachments) R.string.chat_busy_menu_stack_no_attachments
                                else R.string.chat_busy_menu_stack_desc
                            ),
                            enabled = !hasAttachments,
                            onClick = {
                                showBusyMenu = false
                                onEnqueue?.invoke()
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 气泡菜单条目：图标 + 标题 + 说明；[enabled]=false 置灰不可点。 */
@Composable
private fun BusyMenuItem(
    icon: @Composable (Color) -> Unit,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
    }
    Row(
        modifier = Modifier
            .testTag("chat-busy-menu-item")
            .width(280.dp)
            .clip(ShapeTokens.medium)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = {})
            .padding(horizontal = SpacingTokens.MD.dp, vertical = SpacingTokens.SM.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.MD.dp),
    ) {
        icon(contentColor)
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = AlphaTokens.MUTED),
            )
        }
    }
}
