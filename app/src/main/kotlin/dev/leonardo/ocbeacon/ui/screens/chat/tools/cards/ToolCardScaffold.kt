package dev.leonardo.ocbeacon.ui.screens.chat.tools.cards

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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.leonardo.ocbeacon.ui.screens.chat.components.CardExpandEnterTransition
import dev.leonardo.ocbeacon.ui.screens.chat.components.occupyBottomGap
import dev.leonardo.ocbeacon.ui.screens.chat.components.CardExpandReveal
import dev.leonardo.ocbeacon.ui.screens.chat.components.CardExpandExitTransition
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.ui.theme.LocalChatDensity
import dev.leonardo.ocbeacon.ui.theme.typography
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.util.copyToClipboard
import dev.leonardo.ocbeacon.ui.components.AmoledSurface
import dev.leonardo.ocbeacon.ui.components.CardStandardBorder
import dev.leonardo.ocbeacon.ui.components.indicators.PulsingDotsIndicator
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.screens.chat.util.performHaptic
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import kotlinx.coroutines.launch
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * #137（D2-L50）：聊天内复制反馈通道——上层（ChatMessageList）注入 Snackbar
 * 实现，统一聊天内复制反馈（原 ToolCardScaffold 用 Toast，与其他复制操作
 * Snackbar 双通道不一致）。未注入时组件内 Toast 兜底。
 */
val LocalCopyFeedback = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

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
 * @param onToggleExpand 标题行被点击时的回调（展开切换）。#215 批2：本体点击是
 *   唯一展开入口（chevron 冗余按钮移除）；无内容（hasContent=false）时标题行不可点
 * @param rightSideExtras 标题行右侧的额外 composable（如 DiffChangesInline）
 * @param titleContent 可选的自定义标题内容。为 null 时使用简单的图标 + 文本行。
 * @param expandedContent 展开时显示的内容
 * @param containerColor 卡片背景色（非 AMOLED）。默认 surface。
 *   AMOLED 下仍为纯黑 + 边框。用于任务类卡片的状态底色语义
 *  （发起=蓝 / 完成=绿 / 失败=红，2026-08-11 用户要求）。
 */
/** 标题行前导图标尺寸(dp)——竖线锚位由此派生(见展开区 Box)。 */
private val LEADING_ICON_SIZE = 16.dp

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
    /**
     * #349：标题行点击覆盖槽——非 null 时本体点击执行它（如 subagent 卡直达
     * 子会话）而非展开；调用方需自带展开替代入口（chevron）。null=默认契约
     * （#215 批2：本体点击=唯一展开入口）。
     */
    onCardClick: (() -> Unit)? = null,
    rightSideExtras: @Composable (RowScope.() -> Unit)? = null,
    trailingExtras: @Composable (RowScope.() -> Unit)? = null,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    expandedContent: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val clipScope = rememberCoroutineScope()
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val expanded = isExpanded
    // #137（D2-L50）：CompositionLocal.current 必须在 composable 上下文读取（onClick 非 composable）
    val copyFeedback = LocalCopyFeedback.current
    val copiedMessage = stringResource(R.string.chat_copied_clipboard)

    AmoledSurface(
        isAmoledDark = isAmoled,
        // 2026-09-20 单行形态裁决(用户:全面 DSH 化,问题/权限/通知类除外)——
        // 工具卡去容器:透明底/无描边/零 elevation,标题行「图标+类型·摘要」
        // 平铺于消息流(DSH web 实证:行式日志流;opencode session-ui 同构)。
        // 16 卡经本 scaffold 一次收口;状态色由 iconTint/title 语义承担。
        normalColor = Color.Transparent,
        shape = ShapeTokens.smallMedium,
        normalBorder = null,
        normalTonalElevation = 0.dp,
        // 2026-08-30 用户裁决：撤销展开补偿（TC-REVEAL 接线退役）
        // 方案 B(间距统一第三步):占位底部收缩(与 ReasoningBlock 同步)
        modifier = modifier.fillMaxWidth().occupyBottomGap()
    ) {
        // 2026-09-20 间距统一裁决:垂直 4→2dp(与 ReasoningBlock 同步——卡↔正文
        // 空白收敛,卡族互相对齐保持)
        Column(
            modifier = Modifier.padding(
                start = SpacingTokens.XS.dp,
                end = SpacingTokens.XS.dp,
                top = 2.dp,
                bottom = 2.dp,
            ),
        ) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：图标 + 标题（点击展开/折叠——#215 批2：本体点击=唯一展开入口，
                // 无内容时禁点避免死点击）
                if (titleContent != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = hasContent || onCardClick != null) {
                                performHaptic(hapticView, hapticOn)
                                onCardClick?.invoke() ?: onToggleExpand()
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
                            .clickable(enabled = hasContent || onCardClick != null) {
                                performHaptic(hapticView, hapticOn)
                                onCardClick?.invoke() ?: onToggleExpand()
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
                            // #432:标题=正文+1sp+Medium(原 labelMedium 12sp 比正文小,
                            // 无层级;跟随 ChatDensity)
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = (LocalChatDensity.current.typography.bodyFontSize.value + 1f).sp,
                                fontWeight = FontWeight.Medium,
                            ),
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
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)
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
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)
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
                                        clipboard.copyToClipboard("copy", copyText)
                                    }
                                    // #137（D2-L50）：优先走上层 Snackbar 通道（统一反馈），未注入时 Toast 兜底
                                    if (copyFeedback != null) {
                                        copyFeedback()
                                    } else {
                                        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.chat_copy),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                                )
                            }
                        }
                        // #215 批2：展开/折叠 chevron 冗余按钮移除——本体点击已承担
                        // 展开/收起（历史：2026-08-12 纯 Icon 无 onClick bug 曾改为
                        // IconButton；统一交互契约后该入口整体不再需要）
                    }
                }
            }

            // 展开的内容（2026-08-30 用户裁决：统一顶边垂直揭幕，见 CardExpandTransitions.kt）
            CardExpandReveal(
                visible = expanded && hasContent,
            ) {
                // 2026-09-20 单行形态:展开区左竖线层级(Roo 式 border-l,零背景)——
                // 修饰必须在 Reveal content 内部(#420 硬地板教训:外层固定
                // padding 会成为末帧塌陷的落地硬面)。竖线随 fraction 同步揭示。
                val guideColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                Box(
                    modifier = Modifier
                        // 2026-09-20 居中修正:竖线与图标同在 Column 内容区(同一起点
                        // x=0),图标中心=LEADING_ICON_SIZE/2=8dp;线宽 2dp → 锚位
                        // =中心−1dp=7dp。公式化绑定:图标尺寸变更自动跟随。
                        .padding(start = LEADING_ICON_SIZE / 2 - 1.dp)
                        // 竖线画在 Box 左缘(x=1dp 处,2dp 宽)
                        .drawBehind {
                            drawRect(
                                color = guideColor,
                                topLeft = Offset(1.dp.toPx(), 0f),
                                size = Size(2.dp.toPx(), size.height),
                            )
                        }
                        // 2026-09-20 用户反馈修:竖线→内容之间补缩进——原实现内容
                        // 紧贴竖线(视觉粘连);Roo border-l 语义=竖线后留白(pl-4)
                        .padding(start = SpacingTokens.SM.dp),
                ) {
                    expandedContent()
                }
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
