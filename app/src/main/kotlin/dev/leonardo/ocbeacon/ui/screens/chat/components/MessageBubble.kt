package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ChatDensity
import dev.leonardo.ocbeacon.ui.theme.LocalChatDensity
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import dev.leonardo.ocbeacon.util.DateFormatters
import java.util.Date

/**
 * 统一消息气泡容器（2026-08-12 用户要求：标签栏/正文栏/统计栏样式强一致）。
 *
 * **2026-09-12 旧裁决回写（消息层扁平化 spec / GitHub #11）**：「三气泡统一容器」
 * 于 2026-09-12 被本设计**部分反转**——**仅智能体正文**去容器（flat=true 委派
 * [MessageSectionScaffold]，无背景 / 无边框 / 无圆角）；**用户消息保留本气泡**
 * （flat=false：primaryContainer 底色 + AMOLED 描边 + 非对称圆角，2026-09-17 用户裁决）；
 * **通知层（EventCard / 合成通知卡）继续使用本容器**。判据：助手正文 = 内容，
 * 平面；事件 = 通知，成卡；用户消息 = 带气泡的角色消息。
 *
 * 三种角色共用同一外层结构，仅通过参数区分：
 * - [alignEnd]：user 右对齐（true）；assistant/synthetic 左对齐（false）
 * - [containerColor] / [border]：底色与边框（synthetic = 透明 + 边框类型）
 * - [shape]：圆角（user 用聊天气泡非对称圆角；其他用 medium）
 * - 标签栏（非 flat 路径，[showLabelRow]=true）：`[左区 labelLeading?+标签+suffix] [中区 时间] [右区 labelTrailing?]`；
 *   v2 起用户气泡传 showLabelRow=false（角色文字已删），助手正文走 flat。
 * - 统计栏可选（synthetic / 通知卡用；角色消息 v2 走 [MessageSectionScaffold] 尾部）
 */
@Composable
internal fun MessageBubble(
    alignEnd: Boolean,
    containerColor: Color,
    /** 标签栏文案（非 flat 路径用）；flat 路径无标签栏 → 可省略。 */
    label: String = "",
    /** 标签栏时间戳；flat 路径无标签栏 → 可省略。 */
    timeMs: Long = 0L,
    modifier: Modifier = Modifier,
    shape: Shape = ShapeTokens.medium,
    border: BorderStroke? = null,
    labelLeading: (@Composable () -> Unit)? = null,
    /** label 之后的附加内容（如状态文案 + 状态图标——合成通知用）。 */
    labelSuffix: (@Composable () -> Unit)? = null,
    labelTrailing: (@Composable RowScope.() -> Unit)? = null,
    statsBar: (@Composable RowScope.() -> Unit)? = null,
    /** 卡片级点击（#234 事件卡展开/收起用）；null 时不可点（用户/智能体气泡不受影响）。 */
    onCardClick: (() -> Unit)? = null,
    /** 标签行水平内边距（#234 V6 反馈：事件卡标题行右贴边）；null=沿用内容内边距
     *  （用户/智能体气泡默认路径，渲染几何不变）。 */
    labelRowHorizontalPadding: androidx.compose.ui.unit.Dp? = null,
    /** #389 三轮c：内容栏展开态。null＝恒渲染（用户/智能体气泡——内容常驻，
     *  缺省零变化）；非 null＝内容栏整体包 AnimatedVisibility（统一 CardExpand*
     *  动画）——空内容动画收起为 0 高度，不再占 spacedBy 间距（收起态上下边距
     *  对称），展开/收起恒有动画（取代三轮b 的 contentVisible 条件卸载——它把
     *  收起动画截胡成了瞬间消失）。 */
    contentExpanded: Boolean? = null,
    // ---- 2026-09-12 扁平化 / 2026-09-17 v2 两段式 ----
    /** true = 角色消息两段式（无背景 / 无边框 / 无圆角、无头部标签栏）——委派 [MessageSectionScaffold]。
     *  false（默认）= 通知层卡片容器（EventCard / 合成通知卡 / 用户气泡沿用）。 */
    flat: Boolean = false,
    /** 非 flat 路径是否渲染统一标签栏；用户气泡 v2 起传 false（「用户 / 智能体」文字已删）。 */
    showLabelRow: Boolean = true,
    /** 非 flat 路径最大宽度比例（用户消息 0.9）；null = 占满（通知卡）。 */
    maxWidthFraction: Float? = null,
    /** flat 模式：统计栏之下的附加尾部内容（产出文件行等）。 */
    tailExtra: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val compact = LocalChatDensity.current == ChatDensity.Compact

    if (flat) {
        MessageSectionScaffold(
            modifier = modifier,
            alignEnd = alignEnd,
            tail = {
                if (statsBar != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        statsBar(this)
                    }
                }
                tailExtra?.invoke(this)
            },
            content = content,
        )
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        // 2026-08-12 M3 优化：Surface → M3 Card（Filled/Outlined 通用——shape/
        // colors/border/elevation 全参数化，支持气泡样式；阴影设 0 保持气泡观感）
        Card(
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = border,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth(maxWidthFraction ?: 1f)
        ) {
            Column(
                modifier = Modifier
                    .clickable(enabled = onCardClick != null) { onCardClick?.invoke() }
                    .padding(vertical = if (compact) SpacingTokens.SM.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) SpacingTokens.XS.dp else 10.dp)
            ) {
                // 水平缩进下沉到节级（原为 Column 级整段 padding）——渲染几何等价；
                // 拆开的目的是让标签行可独立收窄内边距（标题行贴边，#234 V6 反馈）。
                val contentHPad = if (compact) 10.dp else SpacingTokens.LG.dp
                // ① 标签栏（统一）三段式：[左区: 前导图标+标签+suffix] [中区: 时间] [右区: trailing 组]
                // #312③（2026-09-10 用户裁决「时间放在中央」）：左右两区等权（weight 1f），
                // 时间恒在整行水平中央（titlebar 模式）；labelFillRemaining 机制随之退役。
                // 时间格式＝绝对（messageTimestamp：当天 HH:mm:ss、跨天 yyyy-MM-dd HH:mm:ss）。
                // v2：用户气泡传 showLabelRow=false —— 标签栏整体不渲染。
                if (showLabelRow) {
                    val timeText = remember(timeMs) {
                        DateFormatters.messageTimestamp(timeMs)
                    }
                    Row(
                        modifier = Modifier
                            .padding(horizontal = labelRowHorizontalPadding ?: contentHPad),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)
                    ) {
                        // 左区（weight 1f）：图标 + 标签（区内省略） + suffix
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            labelLeading?.invoke()
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            labelSuffix?.invoke()
                        }
                        // 中区：绝对时间（整行中央）
                        Text(
                            text = timeText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                        )
                        // 右区（weight 1f，尾对齐）：trailing 图标组
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp, Alignment.End),
                            modifier = Modifier.weight(1f),
                        ) {
                            labelTrailing?.invoke(this)
                        }
                    }
                }

                // ② 正文栏（水平缩进在节级；内层 spacedBy 复刻原 Column 级间距）
                // #389 三轮c：contentExpanded 非 null 时整体 AnimatedVisibility
                //（统一 CardExpand* 动画；空内容动画归零 → 不占 spacedBy 间距）。
                val contentBody: @Composable () -> Unit = {
                    Column(
                        modifier = Modifier.padding(horizontal = contentHPad),
                        verticalArrangement = Arrangement.spacedBy(if (compact) SpacingTokens.XS.dp else 10.dp)
                    ) {
                        content()
                    }
                }
                if (contentExpanded == null) {
                    contentBody()
                } else {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = contentExpanded,
                        enter = CardExpandEnterTransition,
                        exit = CardExpandExitTransition,
                    ) {
                        contentBody()
                    }
                }

                // ③ 统计栏（可选）
                if (statsBar != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = contentHPad)
                            .padding(top = if (compact) SpacingTokens.XS.dp else SpacingTokens.SM.dp),
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        statsBar(this)
                    }
                }
            }
        }
    }
}

/** 统一气泡圆角（user 聊天气泡非对称样式）。 */
internal val UserBubbleShape: Shape = RoundedCornerShape(
    topStart = 18.dp,
    topEnd = 4.dp,
    bottomStart = 18.dp,
    bottomEnd = 18.dp
)
