package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.AgentError
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens

/**
 * 统一事件卡（#234 对话流事件卡片统一——spec §1–§2 严格同构模子）。
 *
 * 三种 SSE 事件元素（task/subagent 完成、shell 后台完成、system 目录变更）
 * 共用同一组件与视觉语言，仅参数不同（参数表见 spec §2）：
 *
 * - **容器**（Q2/Q3）：MessageBubble 同构——左对齐、透明底、1dp outline 描边
 *   （失败=AgentError 描边）、ShapeTokens.medium 圆角（直接复用 MessageBubble）。
 * - **标签行**（Q9 单行）：时间戳 · 类型图标（13dp）· 事件标签 · 弹性空隙 ·
 *   跳转箭头（可选常驻，#216 守恒——位置不变）· chevron（有正文时常驻）。
 * - **描述行**（Q15，2026-08-27 开工裁决）：可选槽位，描述数据实际存在才激活——
 *   task=任务描述 / shell=命令预览 / system 无此数据不激活；一行截断。
 * - **严重度编码**（Q5）：成功/信息中性灰（图标+描边全中性）；只有失败用
 *   AgentError + ErrorOutline 图标替换类型图标。
 * - **展开态**（Q11 两段式）：分隔线 → 正文区（Markdown，heightIn(max=300dp)
 *   内部 verticalScroll——**修饰符顺序铁律：heightIn 在 verticalScroll 之外**，
 *   #232 勘误三教训）→ 分隔线 → 动作区按钮行（有则显示）。分隔线统一设计
 *   （2026-08-30）：内缩对齐正文栅格 + 呼吸间距（见 [bodyTopGap]）。
 * - **展开态记忆**（§4/#227 模式）：调用方持有屏幕级 messageId→expanded 表传入，
 *   滚出视口不丢、离会话即清；本组件只读写 [eventKey] 单键。
 * - **无动画**（Q12）：不做弹入/展开动画，交 LazyColumn 列表锚定稳定。
 * - 存量兼容（Q13）：渲染是客户端职责——历史消息同走本卡。
 *
 * 调用方契约：
 * - [eventKey] 全局唯一（约定 = 消息 id）；[expandedStates] 由会话屏持有。
 * - 失败态由调用方传 [failed]=true 并自行选择失败标签文案（spec §2「失败标签」行）。
 * - 展开正文走 [bodyContent]（内部已包 SelectionContainer + 滚动容器）；
 *   Markdown 渲染由调用方用既有 MarkdownContent 组装（SSE 铁律路径不动）。
 */
@Composable
internal fun EventCard(
    eventKey: String,
    timeMs: Long,
    label: String,
    leadingIcon: ImageVector,
    expandedStates: MutableMap<String, Boolean>,
    modifier: Modifier = Modifier,
    failed: Boolean = false,
    description: String? = null,
    bodyContent: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    /** 跳转子会话目标 id（存在即显示常驻箭头——#216 入口守恒；调用方决定哪类事件给箭头）。 */
    navTargetId: String? = null,
    onNavClick: ((String) -> Unit)? = null,
    /** 展开正文字号缩放系数（LocalDensity 密度缩放实现——同缩字号与间距）。
     *  默认 1f 不缩；长 Markdown 报告场景传 ~0.85f 小一档（V6 反馈定档）。 */
    bodyFontScale: Float = 1f,
    /** 无记忆时的初始展开态（#252 终审：用户发起的 shell 卡默认展开，
     *  agent 工具卡等其余事件卡保持收起）。显式 toggle 后以记忆为准。 */
    defaultExpanded: Boolean = false,
    /** 展开态描述行→正文之间的分隔线（Q11 两段式上边）开关。默认 true 保留
     *  原设计（Markdown 裸文本正文需要分隔）。分隔线水平随内容栏内缩 16dp、
     *  线→正文默认 8dp 呼吸（2026-08-30 统一设计，见 bodyTopGap）。 */
    bodyTopDivider: Boolean = true,
    /** 上分隔线→正文首元素的呼吸间距。裸文本/Markdown 用默认 8dp；
     *  正文以自带背景的面块开头（shell 卡 ShellOutputBlock 的
     *  surfaceContainer 圆角块）传 10dp——面块与线贴边即 2026-08-29
     *  「双重分隔」突兀感的根源，恢复线必须留出脱开距离。 */
    bodyTopGap: Dp = 8.dp,
) {
    val expanded = expandedStates[eventKey] ?: defaultExpanded
    val hasBody = bodyContent != null

    // Q5 严重度编码：失败破色只作用图标与描边，其余保持中性
    val labelIcon = if (failed) Icons.Outlined.ErrorOutline else leadingIcon
    val iconTint = if (failed) {
        AgentError
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
    }
    val borderColor = if (failed) {
        AgentError.copy(alpha = AlphaTokens.MEDIUM)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = AlphaTokens.MEDIUM)
    }

    MessageBubble(
        alignEnd = false,
        containerColor = Color.Transparent,
        border = BorderStroke(1.dp, borderColor),
        shape = ShapeTokens.medium,
        label = label,
        labelLeading = {
            Icon(
                imageVector = labelIcon,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = iconTint,
            )
        },
        timeMs = timeMs,
        // V6 反馈：标题行右贴边——chevron 不再悬在 16dp 内容缩进处；
        // 8dp 保持与圆角描边的呼吸空间（左侧时间戳同步左移，两侧对称收窄）
        labelRowHorizontalPadding = 8.dp,
        // F1/V4 复验实证：仅收窄 padding 右缘未生效——根因是双权重均分
        // （label fill=false 与 Spacer 瓜分弹性，trailing 随标题长度浮动）。
        // labelFillRemaining 让 label 独吃弹性，箭头/chevron 恒贴右缘。
        labelFillRemaining = true,
        onCardClick = if (hasBody) ({ expandedStates[eventKey] = !expanded }) else null,
        labelTrailing = {
            // 跳转箭头（Q4 常驻折叠+展开两态；点击不冒泡到整卡 toggle）
            if (navTargetId != null && onNavClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.a11y_icon_navigate_forward),
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { onNavClick(navTargetId) }
                        .padding(3.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (hasBody) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                )
            }
        },
        modifier = modifier,
    ) {
        // 描述行（Q15 可选槽位）：数据在才显示，一行截断；有正文时点它也能 toggle
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 展开态两段式（Q11）：分隔线 → 正文(300dp 上限内滚) → 分隔线 → 动作区
        // 2026-08-30 用户裁决：撤销全部展开补偿改造，回归 AnimatedVisibility
        // 出厂默认动画（spring + fade + 默认揭幕方向）
        AnimatedVisibility(
            visible = hasBody && expanded,
            enter = CardExpandEnterTransition,
            exit = CardExpandExitTransition,
        ) {
            // ★ AnimatedVisibility 内容是 Box 叠放语义（非 Column）——多子级全部
            // 原点重叠：分割线被正文整体盖住（透明 Markdown 时从字底透出、
            // ShellOutputBlock 不透明后彻底消失）——这就是分割线「时隐时现/
            // 不协调」的结构性根因（2026-08-30 像素取证实锤）。显式 Column
            // 恢复正确堆叠：线 → 正文 → 线 → 动作区 各占一行。
            Column(modifier = Modifier.fillMaxWidth()) {
            // 分隔线统一设计（2026-08-30）：水平已随 MessageBubble 内容栏内缩
            // 16dp（勿再加 padding——双层内缩会让线比正文更缩 16dp，真机像素
            // 实测教训）；线→正文 = bodyTopGap；下线（有动作区才有）上下各 8dp。
            // ★ 分隔线必须位于 body 滚动 Column 内部（见下方「硬地板」注释）。
            val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            // #232 勘误三教训：heightIn 必须在 verticalScroll 之外（反序即崩）。
            // clipToBounds：Compose 滚动容器默认不裁剪溢出绘制——不加会压住相邻
            // 消息（真机 V6 反馈「回复重叠」的头号嫌疑；#231 同类坑先例）。
            // ★ 硬地板教训（2026-08-30 逐帧 trace）：分隔线若作为本 Column 的
            // 兄弟级（AV 收缩约束的直接子级），其固定尺寸（1dp+30dp gap）成为
            // 收缩地板——AV 高度 < 33px 后 wrapper 无法再缩，退出完成时 33px
            // 单帧砸掉 = 收起末帧 -30 下跳（展开首帧 +30 同理）。置于滚动
            // Column 内部后由滚动容器吸收任意约束，全程平滑。
            val density = LocalDensity.current
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .clipToBounds()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (bodyTopDivider) {
                    HorizontalDivider(
                        color = dividerColor,
                        modifier = Modifier
                            .padding(bottom = bodyTopGap)
                            .fillMaxWidth(),
                    )
                }
                SelectionContainer {
                    if (bodyFontScale != 1f) {
                        CompositionLocalProvider(
                            LocalDensity provides Density(density.density * bodyFontScale, density.fontScale)
                        ) { bodyContent!!() }
                    } else {
                        bodyContent!!()
                    }
                }
            }
            if (actions != null) {
                HorizontalDivider(
                    color = dividerColor,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    actions()
                }
            }
            } // ★ 显式 Column 收尾（见上「Box 叠放语义」注释）
        }
    }
}
