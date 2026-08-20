package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.normalizeForRender
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.logging.AppLogger
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.components.ConfirmDialog
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.isBubbleRenderablePart
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.ImageThumbnailRow
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.leonardo.ocbeacon.ui.screens.chat.util.performHaptic
import dev.leonardo.ocbeacon.ui.screens.chat.util.resolveUserCommandLabel
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.QueuedBadgeColor
import dev.leonardo.ocbeacon.ui.theme.QueuedBadgeTextColor
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 用户消息气泡——统一容器（MessageBubble）：
 * 标签栏（时间 + "用户"）+ 正文（文本/图片/补丁）+ 统计栏（QUEUED/撤销/复制）。
 * 右对齐 + primaryContainer 底色 + 聊天气泡非对称圆角。
 */
@Composable
internal fun MessageCardUser(
    currentMessage: ChatMessage,
    isQueued: Boolean,
    onRevert: (() -> Unit)?,
    onCopyText: (() -> Unit)?,
    isAmoled: Boolean,
) {
    val backgroundColor = MaterialTheme.colorScheme.primaryContainer
    val textColor = if (isAmoled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    val bubbleBorder = if (isAmoled) {
        BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MUTED)
        )
    } else {
        null
    }
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current

    // 过滤用户消息的可见 parts
    val visibleParts = currentMessage.parts.filter { part ->
        when (part) {
            is Part.Text -> part.synthetic != true && part.ignored != true && part.text.isNotBlank()
            else -> true
        }
    }

    val userMessage = currentMessage.message as? Message.User
    val userFallbackText = userMessage?.summary?.body?.takeIf { it.isNotBlank() }
        ?: userMessage?.summary?.title?.takeIf { it.isNotBlank() }
    val userCommandLabel = resolveUserCommandLabel(currentMessage.parts)

    val contentParts = visibleParts

    val hasRenderableUserPart = contentParts.any(::isBubbleRenderablePart)
    if (!hasRenderableUserPart && userFallbackText == null && userCommandLabel == null) {
        return
    }

    var showRevertConfirmation by remember { mutableStateOf(false) }

    // 2026-08-13：将 parts 分组计算提升到 MessageBubble 外——jumpMdState（跳转
    // 预渲染注册 + 淡入）需要在这里创建（content lambda 内定义则外层不可见）。
    val (imageFiles, renderableOtherParts) = remember(contentParts) {
        val images = contentParts.filterIsInstance<Part.File>()
            .filter { it.mime.startsWith("image/") && !it.url.isNullOrBlank() }
        val others = contentParts.filter { part ->
            !(part is Part.File && part.mime.startsWith("image/") && !part.url.isNullOrBlank())
        }.filter(::isBubbleRenderablePart)
        images to others
    }

    // 2026-08-21 卫生清理（D-10/#11-4）：user 消息的 readiness 订阅、
    // jumpTextPart/jumpMdState（LocalMarkdownStateRegistry 注册链）与 Ready
    // 上报链全部删除——PartContent isUser 分支纯 Text 渲染（MarkdownState/
    // preParsedState 均被忽略），且滚动预解析驱动只处理 assistant 消息，
    // user 条目在注册表中的状态本就无人消费。

    // 2026-08-13 架构根治：门控展示从状态机派生（Displayed/Failed 前 alpha=0
    // 透明——渲染/测量/收敛全部在不可见状态完成；状态机终点后恒显示——不受
    // readiness 波动影响）。非目标恒 1。
    val jumpController = LocalJumpController.current
    val isJumpObserveTarget = jumpController.currentTargetMsgId == currentMessage.message.id
    val jumpPhase by jumpController.phase.collectAsState()
    val jumpReady = !isJumpObserveTarget ||
        jumpPhase is JumpPhase.Displayed || jumpPhase is JumpPhase.Failed
    val jumpAlpha = if (jumpReady) 1f else 0f


    MessageBubble(
        alignEnd = true,
        containerColor = backgroundColor,
        border = bubbleBorder,
        shape = UserBubbleShape,
        label = stringResource(R.string.chat_label_user),
        // 2026-08-16（标题栏规范·类型图标）：用户=Person，14dp FAINT
        labelLeading = {
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.Person,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
            )
        },
        timeMs = currentMessage.message.time.created,
        modifier = if (isJumpObserveTarget) {
            Modifier.graphicsLayer { alpha = jumpAlpha }
        } else {
            Modifier
        },
        statsBar = {
            // 弹性空白
            Spacer(modifier = Modifier.weight(1f))

            // 右侧：状态指示器（QUEUED 徽章）
            // 悲观模式：无 Sending/Failed/Sent 状态（消息以服务器权威直接出现）。
            // 仅保留 QUEUED 徽章（FSM 队列状态派生）。
            // 2026-08-12：CompactTag（与输入组件同款，高度自适应）
            if (isQueued) {
                CompactTag(
                    text = stringResource(R.string.chat_queued),
                    containerColor = QueuedBadgeColor,
                    contentColor = QueuedBadgeTextColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8
                )
            }

            // Undo 按钮（仅主会话，onRevert != null 时显示）
            if (onRevert != null) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(R.string.chat_revert),
                    modifier = Modifier
                        .size(14.dp)
                        .clickable {
                            performHaptic(hapticView, hapticOn)
                            showRevertConfirmation = true
                        },
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                )
            }

            // Copy 按钮（最右侧）
            if (onCopyText != null) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.chat_copy),
                    modifier = Modifier
                        .size(14.dp)
                        .clickable {
                            performHaptic(hapticView, hapticOn)
                            onCopyText()
                        },
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                )
            }
        }
    ) {
        // 内容 parts（文本、推理、补丁等）
        // 2026-08-13：imageFiles/renderableOtherParts/jumpMdState 已提升到
        // MessageBubble 外层（跳转预渲染 + 淡入需要）——此处直接使用。

        // 以水平行渲染图片缩略图
        if (imageFiles.isNotEmpty()) {
            ImageThumbnailRow(imageFiles = imageFiles)
        }

        // 渲染剩余 parts
        for (part in renderableOtherParts) {
            key(part.id) {
                PartContent(
                    part = part,
                    textColor = textColor,
                    isUser = true,
                    onViewSubSession = null
                )
            }
        }

        if (imageFiles.isEmpty() && renderableOtherParts.isEmpty() && userCommandLabel != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.RateReview,
                    contentDescription = stringResource(R.string.a11y_icon_rate_review),
                    modifier = Modifier.size(16.dp),
                    tint = textColor.copy(alpha = AlphaTokens.MEDIUM)
                )
                Text(
                    text = userCommandLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor.copy(alpha = AlphaTokens.AMOLED)
                )
            }
        }

        // 若文本 parts 缺失但服务器提供了摘要，则渲染摘要。
        if (visibleParts.isEmpty() && userFallbackText != null) {
            Text(
                text = userFallbackText,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor.copy(alpha = AlphaTokens.MUTED)
            )
        }
    }

    // 撤回确认对话框
    if (showRevertConfirmation && onRevert != null) {
        ConfirmDialog(
            title = stringResource(R.string.chat_revert),
            message = stringResource(R.string.chat_revert_message),
            confirmLabel = stringResource(R.string.chat_revert),
            onDismiss = { showRevertConfirmation = false },
            onConfirm = {
                showRevertConfirmation = false
                onRevert()
            },
        )
    }
}
