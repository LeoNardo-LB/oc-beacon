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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
 *   #232 勘误三教训）→ 分隔线 → 动作区按钮行（有则显示）。
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
    /** #241 标签行保护（渲染前补偿）：传入会话 LazyListState 即启用一次性
     *  展开揭示（ExpandReveal）——增长遍裁剪 + 反射注入视窗下移、下一遍对齐
     *  揭示，全程无可见滚动动画；null = 不补偿。 */
    expandRevealListState: LazyListState? = null,
) {
    val expanded = expandedStates[eventKey] ?: false
    val hasBody = bodyContent != null

    // #241 渲染前补偿：展开增量裁剪 + 遍首注入视窗下移、下一遍对齐揭示
    val expandReveal = remember { ExpandRevealCompensator() }

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
        modifier = modifier.then(
            if (expandRevealListState != null && hasBody) {
                Modifier
                    .clipToBounds()
                    .expandRevealCompensation(expandRevealListState, expandReveal, "EV-REVEAL")
            } else {
                Modifier
            }
        ),
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
        // 2026-08-28 二次裁决：恢复展开/收起动画（AV 包裹整段）——瞬时收起的
        // Δ 单帧跳变不可接受；渲染前补偿（EV-REVEAL）在根 modifier 逐帧配对，
        // 动画逐帧增量同样被配对，收起平滑且零漂移。Q12 无动画裁决被取代。
        AnimatedVisibility(visible = hasBody && expanded) {
            val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            HorizontalDivider(color = dividerColor)
            // #232 勘误三教训：heightIn 必须在 verticalScroll 之外（反序即崩）。
            // clipToBounds：Compose 滚动容器默认不裁剪溢出绘制——不加会压住相邻
            // 消息（真机 V6 反馈「回复重叠」的头号嫌疑；#231 同类坑先例）。
            val density = LocalDensity.current
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .clipToBounds()
                    .verticalScroll(rememberScrollState()),
            ) {
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
                HorizontalDivider(color = dividerColor)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    actions()
                }
            }
        }
    }
}
