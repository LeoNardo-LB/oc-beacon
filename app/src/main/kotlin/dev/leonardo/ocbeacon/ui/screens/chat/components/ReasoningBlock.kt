package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.components.CardStandardBorder
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownContent
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.leonardo.ocbeacon.ui.screens.chat.util.halfScreenHeight
import dev.leonardo.ocbeacon.ui.screens.chat.util.performHaptic
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.AppMotion
import kotlinx.coroutines.delay
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * #263 round2：完结思考卡的显示时长合成。优先服务器可信时长（>0）；未知
 * （null/0/负，含 start=0 哨兵守卫后）退回本地冻结实测值；两者皆无 → null
 * （显示层回落静态文案，绝不显示伪造时长）。
 */
internal fun resolveReasoningDisplayDuration(durationMs: Long?, frozenElapsedMs: Long): Long? =
    durationMs?.takeIf { it > 0 } ?: frozenElapsedMs.takeIf { it > 0 }

@Composable
internal fun ReasoningBlock(text: String, isExpanded: Boolean = false, onToggleExpand: () -> Unit = {}, durationMs: Long? = null, isStreaming: Boolean = false, startTimeMs: Long? = null) {
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val expanded = isExpanded

    // 流式推理的实时计时器
    // #207：fallback 锚点 remember → rememberSaveable。time=null 的残留 part 无
    // 数据锚，原实现每次滑出视口销毁后重组合都取新时钟 → 计时反复归零；
    // saveable 经 LazyColumn item key 保留，同屏内滑回不重置（值语义：自
    // 首次组合续计——配合 isReasoningStreaming 的 idle 静态化，此路径仅活体
    // 重进错过 started 时触达）。
    val fallbackStart = rememberSaveable { mutableStateOf(System.currentTimeMillis()) }.value
    val effectiveStart = startTimeMs ?: fallbackStart
    val elapsedMs = remember { mutableLongStateOf(0L) }
    // #263 round2：本地实测时长的冻结样本——流式 tick 期间持续捕获。服务器未给
    // 可信 start（0 哨兵/缺失）时，完结卡显示冻结值而非 0ms/伪造值。0 = 从未
    // tick（历史卡后进入视口，无本地观测）。
    val frozenElapsedMs = remember { mutableLongStateOf(0L) }
    LaunchedEffect(isStreaming, effectiveStart) {
        if (isStreaming) {
            while (true) {
                // 下限钳制为 0 —— 服务器时钟偏差可能使其为负
                elapsedMs.longValue = (System.currentTimeMillis() - effectiveStart).coerceAtLeast(0L)
                frozenElapsedMs.longValue = elapsedMs.longValue
                // 2026-08-15 用户要求：0.3s ticker（秒级小数进度感；独立 state，
                // 重组范围仅限本组件，与 #47/L-10 的重组治理不冲突）
                delay(100L)
            }
        } else {
            elapsedMs.longValue = resolveReasoningDisplayDuration(durationMs, frozenElapsedMs.longValue) ?: 0L
        }
    }

    val accentColor = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM)
    val containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = AlphaTokens.MEDIUM)
    val textColor = MaterialTheme.colorScheme.onSurface

    // #135（D2-L45）：脉冲动画仅"思考中"运行——已完成/折叠的思考卡片
    // 原实现 rememberInfiniteTransition 无条件 60fps 渲染帧（动画值虽未被
    // drawBehind 使用，仍持续驱动重组）；isComplete 时用静态 alpha。
    // #207：非流式（含 idle 会话下 time=null 残留卡）同样用静态 alpha——
    // 不再对停表卡片播放"正在思考"脉冲误导。
    // #263 round2：完结显示时长 = 服务器可信值 → 本地冻结实测值 → 无。0/负值
    // 一律视为未知（不得显示 0ms）。
    val displayDurationMs = resolveReasoningDisplayDuration(durationMs, frozenElapsedMs.longValue)
    val isComplete = !isStreaming && (displayDurationMs != null || durationMs != null)
    val pulseAlpha: Float = if (isComplete || !isStreaming) {
        0.4f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "thinkingPulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes { durationMillis = AppMotion.PULSE_CYCLE; 0.7f at 400; 0.4f at 800 },
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )
        alpha
    }
    val headerText = when {
        isStreaming -> stringResource(R.string.chat_thinking_in_progress, formatReasoningDuration(elapsedMs.longValue))
        // #338：时长未知（durationMs 零/负且无本地冻结样本——DSH 整装事件
        // start=end 同信封族）→ 无时长变体，不显示伪造 0ms（#263 round2 哲学收口）。
        isComplete -> displayDurationMs
            ?.let { stringResource(R.string.chat_thinking_complete, formatReasoningDuration(it)) }
            ?: stringResource(R.string.chat_thinking_complete_unknown)
        else -> stringResource(R.string.chat_status_thinking)
    }

    Surface(
        // 2026-09-20 单行形态裁决(全面 DSH 化):思考卡去容器——透明底/无描边,
        // 「脉冲点+思考·摘要」平铺消息流(DSH web 实证:灰度弱化单行,无边框
        // 底色);2.5dp 强调色条同步移除(DSH 无此元素,脉冲点已承担流式指示)。
        shape = ShapeTokens.smallMedium,
        color = Color.Transparent,
        border = null,
        // 方案 B(间距统一第三步):占位底部收缩——见 occupyBottomGap 文档
        modifier = Modifier.fillMaxWidth().occupyBottomGap()
    ) {
        Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // #215 批3（推翻 2026-08-16 卡片职责分离规范，用户授权）：
                    // 标题行本体点击=展开/收起唯一入口（与 scaffold 家族统一），
                    // 右侧 chevron 按钮移除；复制维持内容区 SelectionContainer 选中。
                    // 2026-08-16（用户反馈）：折叠态行高与其他卡片单行一致——
                    // 垂直 padding 8dp → 4dp（对齐 ToolCardScaffold 的
                    // Column padding(SpacingTokens.XS.dp)），总高 ~36dp 与工具卡折叠态等高。
                    // 2026-09-20 间距统一裁决:垂直 4→2dp——实测卡↔正文空白 63px
                    // (卡内留白 20px/侧 × 2 + sectionGap 8dp + leading),为正文行间
                    // 24px 的 2.6 倍;收敛卡内留白 20→14px(与 ToolCardScaffold 同步)。
                    // 2026-09-20 单行形态:水平 padding 对齐工具卡 scaffold(XS)——
                    // 原 MD(12) 是给 2.5dp 色条让位的档位,色条已移除。
                    .padding(start = SpacingTokens.XS.dp, end = 10.dp, top = 2.dp, bottom = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 2026-09-20 方案A回退(用户裁决:还是正常卡片就行)——
                        // 强制 height(12dp) 单行胶囊只瘦了思考卡,工具卡未同步,
                        // 卡族折叠态高度失配=「不协调」来源,且违背 2026-08-16
                        // 「折叠行高与工具卡一致」裁决。恢复自然行高。
                        .clickable { performHaptic(hapticView, hapticOn); onToggleExpand() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        // 动画脉冲圆点（仅在思考时显示）
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .drawBehind {
                                    drawCircle(
                                        color = accentColor.copy(alpha = pulseAlpha)
                                    )
                                }
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = headerText,
                            style = MaterialTheme.typography.labelMedium,
                            color = textColor.copy(alpha = AlphaTokens.MUTED),
                            maxLines = 1,
                        )
                        // 2026-08-16（用户反馈）：流式占位进度圈并入标题行内——
                        // 原实现单独占一行使折叠态高度翻倍，超出其他卡片单行高度。
                        if (isStreaming && text.isBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = accentColor.copy(alpha = AlphaTokens.MUTED)
                            )
                        }
                    }
                    // #215 批3：chevron IconButton 移除——本体点击=展开唯一入口
                }

                // 可展开内容——#420(2026-09-20 用户裁决 A):原地揭示补偿
                // (单一时钟同帧配对,展开/收起不再把高度变化转译为视口跳动;
                // 降级路径=出厂 AV,行为与 2026-08-30 终局一致)
                CardExpandReveal(visible = expanded) {
                    // 2026-09-20 单行形态:展开区左竖线(Roo 式,与工具卡同语言;
                    // 修饰在 Reveal content 内部——#420 硬地板教训)
                    val guideColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                    Box(
                        modifier = Modifier
                            .padding(start = SpacingTokens.SM.dp)
                            .drawBehind {
                                drawRect(
                                    color = guideColor,
                                    topLeft = Offset(1.dp.toPx(), 0f),
                                    size = Size(2.dp.toPx(), size.height),
                                )
                            },
                    ) {
                        Column {
                        Spacer(modifier = Modifier.height(6.dp))
                        // 2026-08-16（用户反馈调整）：高度上限从半屏收紧为固定值——
                        // 思考内容是长 Markdown，半屏上限下总是顶满（其他工具卡片
                        // 内容短、实际远达不到半屏上限），视觉上显著高于其他卡片。
                        // 240.dp 与多数工具卡片展开态的实际视觉高度一致。
                        val reasoningScrollState = rememberScrollState()
                        // clipToBounds：同 #234 二轮防御——滚动容器默认不裁剪溢出绘制
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .clipToBounds()
                                .verticalScroll(reasoningScrollState)
                        ) {
                            // 2026-08-16（部分复制）：SelectionContainer 包裹内容——
                            // 用户可选中任意片段复制（与 ReadToolCard 一致），
                            // 替代此前的整卡全量复制按钮。
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                MarkdownContent(
                                    markdown = text,
                                    textColor = textColor.copy(alpha = AlphaTokens.MUTED),
                                    isUser = false,
                                    customFontSize = "small"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 方案 B（2026-09-20 间距统一第三步）：卡片**占位底部收缩** [CARDS_BOTTOM_SHRINK]。
 *
 * 实测卡背景↔正文字形 49px(无 emoji 基准) = spacedBy 8dp(24px) + Markdown
 * 组件首段固有顶部空 ~25px(行高 leading + 库内行为,不可配)——为正文行间
 * 24px 的 2 倍。本修饰符把卡片占位高度上收 [CARDS_BOTTOM_SHRINK]：
 * spacedBy 从收缩后的占位底起算 → 下一元素上移 → 视觉间隙收敛到正文
 * 行间同档。卡片背景绘制溢出占位（Column 不裁剪),溢出区与正文首行
 * leading 空白重叠、不碰字形。
 * - #420 安全：收缩量为常量，toggle 间占位 delta == 视觉 delta；
 * - 上侧间隙不受影响（占位顶=视觉顶）；
 * - 卡族同步：ToolCardScaffold 同款引用，保持互相对齐。
 */
internal fun Modifier.occupyBottomGap(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shrink = CARDS_BOTTOM_SHRINK.roundToPx()
    layout(placeable.width, (placeable.height - shrink).coerceAtLeast(0)) {
        placeable.placeRelative(0, 0)
    }
}

/** 卡族占位底部收缩量(dp)——首段固有顶部空 25px 的 dp 取整。 */
internal val CARDS_BOTTOM_SHRINK = 8.dp

private fun formatReasoningDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "${"%.1f".format(ms / 1000.0)}s"
    else -> {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        if (s == 0L) "${m}m" else "${m}m ${s}s"
    }
}
