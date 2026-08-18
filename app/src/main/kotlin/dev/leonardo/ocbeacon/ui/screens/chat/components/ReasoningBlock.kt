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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.components.EmbeddedCardContainer
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownContent
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.leonardo.ocbeacon.ui.screens.chat.util.halfScreenHeight
import dev.leonardo.ocbeacon.ui.screens.chat.util.performHaptic
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.AppMotion
import kotlinx.coroutines.delay

@Composable
internal fun ReasoningBlock(text: String, isExpanded: Boolean = false, onToggleExpand: () -> Unit = {}, durationMs: Long? = null, isStreaming: Boolean = false, startTimeMs: Long? = null) {
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val expanded = isExpanded

    // 流式推理的实时计时器
    val fallbackStart = remember { System.currentTimeMillis() }
    val effectiveStart = startTimeMs ?: fallbackStart
    val elapsedMs = remember { mutableLongStateOf(0L) }
    LaunchedEffect(isStreaming, effectiveStart) {
        if (isStreaming) {
            while (true) {
                // 下限钳制为 0 —— 服务器时钟偏差可能使其为负
                elapsedMs.longValue = (System.currentTimeMillis() - effectiveStart).coerceAtLeast(0L)
                // 2026-08-15 用户要求：0.3s ticker（秒级小数进度感；独立 state，
                // 重组范围仅限本组件，与 #47/L-10 的重组治理不冲突）
                delay(100L)
            }
        } else {
            elapsedMs.longValue = durationMs ?: 0L
        }
    }

    val accentColor = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM)
    val textColor = MaterialTheme.colorScheme.onSurface

    // #135（D2-L45）：脉冲动画仅"思考中"运行——已完成/折叠的思考卡片
    // 原实现 rememberInfiniteTransition 无条件 60fps 渲染帧（动画值虽未被
    // drawBehind 使用，仍持续驱动重组）；isComplete 时用静态 alpha。
    val isComplete = durationMs != null && !isStreaming
    val pulseAlpha: Float = if (isComplete) {
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
        isComplete -> stringResource(R.string.chat_thinking_complete, formatReasoningDuration(durationMs))
        else -> stringResource(R.string.chat_status_thinking)
    }

    // 2026-08-18 容器统一：直角(0) + 半透明 surfaceContainer → 共享容器
    //（medium 圆角 + 实底 + 1dp 边框）；accent 左条保留（Surface 裁剪随圆角）
    EmbeddedCardContainer(
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 强调色左侧条
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .fillMaxHeight()
                    .background(accentColor)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 2026-08-16（卡片职责分离规范）：卡片本体不可点击——
                    // 展开/收缩与复制由右侧专门按钮承担，避免大区域误触与
                    // 点击落空不可预期（与 CompactionCard/SyntheticNotificationCard
                    // 统一）。
                    // 2026-08-16（用户反馈）：折叠态行高与其他卡片单行一致——
                    // 垂直 padding 8dp → 4dp（对齐 ToolCardScaffold 的
                    // Column padding(4.dp)），总高 ~36dp 与工具卡折叠态等高。
                    .padding(start = 12.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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

                    // 2026-08-16（用户反馈调整）：复制改为**部分复制**——
                    // 内容区 SelectionContainer 选中文本复制（与 ReadToolCard
                    // 等工具卡一致），移除整卡全量 CopyButton。
                    // 展开职责由专门按钮承担（原整卡 clickable 移除）。
                    IconButton(
                        onClick = { performHaptic(hapticView, hapticOn); onToggleExpand() },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded)
                                stringResource(R.string.chat_collapse)
                            else
                                stringResource(R.string.chat_expand),
                            modifier = Modifier.size(18.dp),
                            tint = textColor.copy(alpha = AlphaTokens.FAINT)
                        )
                    }
                }

                // 可展开内容
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(6.dp))
                        // 2026-08-16（用户反馈调整）：高度上限从半屏收紧为固定值——
                        // 思考内容是长 Markdown，半屏上限下总是顶满（其他工具卡片
                        // 内容短、实际远达不到半屏上限），视觉上显著高于其他卡片。
                        // 240.dp 与多数工具卡片展开态的实际视觉高度一致。
                        val reasoningScrollState = rememberScrollState()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
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
