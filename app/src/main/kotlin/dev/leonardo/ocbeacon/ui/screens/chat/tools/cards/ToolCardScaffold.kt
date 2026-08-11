package dev.leonardo.ocbeacon.ui.screens.chat.tools.cards

import android.content.ClipData
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.components.AmoledSurface
import dev.leonardo.ocbeacon.ui.components.indicators.PulsingDotsIndicator
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.screens.chat.util.performHaptic
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import kotlinx.coroutines.launch

/**
 * 所有工具卡片共用的脚手架。
 * 封装通用的 Surface + 标题行 + 展开模式。
 *
 * @param icon 前导图标（16dp）
 * @param iconTint 前导图标的着色
 * @param title 标题文本（[titleContent] 为 null 时使用）
 * @param copyText 通过内置复制按钮复制到剪贴板的文本。为空则隐藏按钮。
 * @param isExpanded 当前展开状态
 * @param isRunning 工具当前是否在运行（显示脉冲圆点）
 * @param hasContent 是否有内容要显示（控制右侧可见性与动画）
 * @param isAmoled AMOLED 主题标志
 * @param onToggleExpand 标题行被点击时的回调（默认展开切换）
 * @param onClick 标题行点击的可选覆盖。为 null 时使用 onToggleExpand。
 * @param rightSideExtras 标题行右侧的额外 composable（如 DiffChangesInline）
 * @param titleContent 可选的自定义标题内容。为 null 时使用简单的图标 + 文本行。
 * @param expandedContent 展开时显示的内容
 * @param showExpandIcon 是否显示展开/折叠 chevron 图标。默认 true。
 * @param containerColor 卡片背景色（非 AMOLED）。默认 surface。
 *   AMOLED 下仍为纯黑 + 边框。用于任务类卡片的状态底色语义
 *  （发起=蓝 / 完成=绿 / 失败=红，2026-08-11 用户要求）。
 */
@Composable
internal fun ToolCardScaffold(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    copyText: String,
    isExpanded: Boolean,
    isRunning: Boolean,
    hasContent: Boolean,
    isAmoled: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    rightSideExtras: @Composable (RowScope.() -> Unit)? = null,
    trailingExtras: @Composable (RowScope.() -> Unit)? = null,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    showExpandIcon: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    expandedContent: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val clipScope = rememberCoroutineScope()
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val expanded = isExpanded

    AmoledSurface(
        isAmoledDark = isAmoled,
        normalColor = containerColor,
        shape = ShapeTokens.smallMedium,
        normalTonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：图标 + 标题（点击展开/折叠）
                if (titleContent != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                performHaptic(hapticView, hapticOn)
                                (onClick ?: onToggleExpand)()
                            }
                    ) {
                        titleContent(this)
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                performHaptic(hapticView, hapticOn)
                                (onClick ?: onToggleExpand)()
                            }
                    ) {
                        Icon(
                            imageVector = icon,
                                contentDescription = stringResource(if (expanded) R.string.a11y_icon_collapse else R.string.a11y_icon_expand),
                            modifier = Modifier.size(16.dp),
                            tint = iconTint
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // 右侧：额外内容 +（运行指示器 或 复制 + 展开）
                if (isRunning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rightSideExtras?.invoke(this)
                        PulsingDotsIndicator(
                            dotSize = 5.dp,
                            dotSpacing = 3.dp,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                } else if (hasContent) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 1. 左侧额外内容（diff 变更指示器）
                        rightSideExtras?.invoke(this)
                        // 2. 尾部额外内容（打开文件按钮）
                        trailingExtras?.invoke(this)
                        // 3. 复制按钮
                        if (copyText.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    clipScope.launch {
                                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("copy", copyText)))
                                    }
                                    Toast.makeText(context, context.getString(R.string.chat_copied_clipboard), Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = context.getString(R.string.chat_copy),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                                )
                            }
                        }
                        // 4. 展开/折叠图标（最右侧）
                        if (showExpandIcon) {
                            Icon(
                                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = title,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                            )
                        }
                    }
                }
            }

            // 展开的内容
            AnimatedVisibility(
                visible = expanded && hasContent,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                expandedContent()
            }
        }
    }
}

/**
 * 引用文件的工具卡片的打开文件图标按钮。
 * 镜像复制按钮的尺寸/着色，使其在旁边保持一致。
 * 放在卡片的 [ToolCardScaffold.rightSideExtras] 槽位中。
 */
@Composable
internal fun RowScope.OpenFileIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(22.dp)
            .testTag("tool_card_open_file")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = stringResource(R.string.a11y_icon_open_file),
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
        )
    }
}
