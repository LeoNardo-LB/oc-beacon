package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.components.ConfirmDialog
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.ImageThumbnailRow
import dev.leonardo.ocbeacon.ui.screens.chat.isBubbleRenderablePart
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.statusBadgeFor
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.leonardo.ocbeacon.ui.screens.chat.util.performHaptic
import dev.leonardo.ocbeacon.ui.screens.chat.util.resolveUserCommandLabel
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/** 用户消息尾部最大宽度（spec：右对齐 + 最大宽度 82%）。 */
private const val USER_MAX_WIDTH_FRACTION = 0.82f

/**
 * 用户消息（2026-09-12 消息层扁平化）——**扁平三段式，去气泡外观**：
 * 头部（Person + 「用户」+ 状态徽标 + 时间）+ 正文（文本/图片/补丁）+
 * 尾部（插话徽标 / 撤销 / 复制 / 更多）。
 *
 * 变化（相对旧 MessageBubble 版本）：无 primaryContainer 底色、无 AMOLED 描边、
 * 无圆角；右对齐 + 最大宽度 82%（US#2）。
 */
@Composable
internal fun MessageCardUser(
    currentMessage: ChatMessage,
    onRevert: (() -> Unit)?,
    onCopyText: (() -> Unit)?,
    isAmoled: Boolean,
    onDeleteMessage: (() -> Unit)? = null,
) {
    val textColor = MaterialTheme.colorScheme.onSurface
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
    var showMoreSheet by remember { mutableStateOf(false) }

    val (imageFiles, renderableOtherParts) = remember(contentParts) {
        val images = contentParts.filterIsInstance<Part.File>()
            .filter { it.mime.startsWith("image/") && !it.url.isNullOrBlank() }
        val others = contentParts.filter { part ->
            !(part is Part.File && part.mime.startsWith("image/") && !part.url.isNullOrBlank())
        }.filter(::isBubbleRenderablePart)
        images to others
    }

    val jumpController = LocalJumpController.current
    val isJumpObserveTarget = jumpController.currentTargetMsgId == currentMessage.message.id
    val jumpPhase by jumpController.phase.collectAsState()
    val jumpReady = !isJumpObserveTarget ||
        jumpPhase is JumpPhase.Displayed || jumpPhase is JumpPhase.Failed
    val jumpAlpha = if (jumpReady) 1f else 0f

    val copyText = contentParts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
    val hasMoreItems = onDeleteMessage != null

    MessageSectionScaffold(
        label = stringResource(R.string.chat_label_user),
        labelLeading = { UserLabelIcon() },
        timeMs = currentMessage.message.time.created,
        alignEnd = true,
        maxWidthFraction = USER_MAX_WIDTH_FRACTION,
        statusBadge = statusBadgeFor(
            isStreaming = false,
            finish = null,
            hasError = false,
        ),
        modifier = if (isJumpObserveTarget) {
            Modifier.graphicsLayer { alpha = jumpAlpha }
        } else {
            Modifier
        },
        tail = {
            if (userMessage?.viaSteer == true || onRevert != null || onCopyText != null || hasMoreItems) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp, Alignment.End),
                ) {
                    if (userMessage?.viaSteer == true) {
                        SteerBadge()
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    // 撤销（仅主会话 / 服务器能力位；US#10）
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
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                        )
                    }
                    // 复制常显（US#9）
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
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                        )
                    }
                    // 更多（删除消息——能力位就绪才出现；US#35）
                    if (hasMoreItems) {
                        Icon(
                            Icons.Filled.MoreHoriz,
                            contentDescription = stringResource(R.string.a11y_message_more),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable {
                                    performHaptic(hapticView, hapticOn)
                                    showMoreSheet = true
                                },
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                        )
                    }
                }
            }
        },
    ) {
        if (imageFiles.isNotEmpty()) {
            ImageThumbnailRow(imageFiles = imageFiles)
        }

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

        if (visibleParts.isEmpty() && userFallbackText != null) {
            Text(
                text = userFallbackText,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor.copy(alpha = AlphaTokens.MUTED)
            )
        }
    }

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

    if (showMoreSheet && onDeleteMessage != null) {
        MessageMoreSheet(
            feedback = null,
            onRate = null,
            onCopyMarkdownSource = null,
            onDelete = onDeleteMessage,
            onDismiss = { showMoreSheet = false },
        )
    }
}

@Composable
private fun UserLabelIcon() {
    Icon(
        imageVector = Icons.Filled.Person,
        contentDescription = null,
        modifier = Modifier.size(13.dp),
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
    )
}

/**
 * #395：插话（steer）徽标——发送路径标记（见 [Message.User.viaSteer]）。
 */
@Composable
private fun SteerBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(SpacingTokens.XS.dp),
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.chat_steer),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = SpacingTokens.XS.dp, vertical = 1.dp),
        )
    }
}

/**
 * 长用户消息分片渲染（2026-08-22 滚动巨帧根治，见 splitUserTextChunks）。
 *
 * 2026-09-12 扁平化：分片不再需要圆角拼接——首段带头部、末段带尾部、
 * 中段纯正文，全部无容器外观（与 ChunkedAssistantMessage 同语言）。
 */
@Composable
internal fun ChunkedUserMessage(
    currentMessage: ChatMessage,
    chunk: ChatEntry.UserChunk,
    onRevert: (() -> Unit)?,
    onCopyText: (() -> Unit)?,
    isAmoled: Boolean,
    onDeleteMessage: (() -> Unit)? = null,
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    var showRevertConfirmation by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }

    val text = chunk.plan.segments[chunk.chunkIndex]
    val hasMoreItems = onDeleteMessage != null

    MessageSectionScaffold(
        label = stringResource(R.string.chat_label_user),
        labelLeading = { UserLabelIcon() },
        timeMs = currentMessage.message.time.created,
        alignEnd = true,
        maxWidthFraction = USER_MAX_WIDTH_FRACTION,
        showHeader = chunk.isFirst,
        showTail = chunk.isLast,
        tail = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                if ((currentMessage.message as? Message.User)?.viaSteer == true) {
                    SteerBadge()
                    Spacer(modifier = Modifier.size(SpacingTokens.SM.dp))
                }
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
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                    )
                    Spacer(modifier = Modifier.size(SpacingTokens.SM.dp))
                }
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
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                    )
                }
                if (hasMoreItems) {
                    Spacer(modifier = Modifier.size(SpacingTokens.SM.dp))
                    Icon(
                        Icons.Filled.MoreHoriz,
                        contentDescription = stringResource(R.string.a11y_message_more),
                        modifier = Modifier
                            .size(16.dp)
                            .clickable {
                                performHaptic(hapticView, hapticOn)
                                showMoreSheet = true
                            },
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                    )
                }
            }
        },
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
            )
        }
    }

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

    if (showMoreSheet && onDeleteMessage != null) {
        MessageMoreSheet(
            feedback = null,
            onRate = null,
            onCopyMarkdownSource = null,
            onDelete = onDeleteMessage,
            onDismiss = { showMoreSheet = false },
        )
    }
}
