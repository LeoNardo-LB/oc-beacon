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
                delay(100L)
            }
        } else {
            elapsedMs.longValue = durationMs ?: 0L
        }
    }

    val accentColor = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM)
    val containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = AlphaTokens.MEDIUM)
    val textColor = MaterialTheme.colorScheme.onSurface

    // 思考圆点的脉冲动画（仅在 durationMs == null = 仍在思考时运行）
    val infiniteTransition = rememberInfiniteTransition(label = "thinkingPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes { durationMillis = AppMotion.PULSE_CYCLE; 0.7f at 400; 0.4f at 800 },
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val isComplete = durationMs != null && !isStreaming
    val headerText = when {
        isStreaming -> stringResource(R.string.chat_thinking_in_progress, formatReasoningDuration(elapsedMs.longValue))
        isComplete -> stringResource(R.string.chat_thinking_complete, formatReasoningDuration(durationMs))
        else -> stringResource(R.string.chat_status_thinking)
    }

    Surface(
        shape = ShapeTokens.none,
        color = containerColor,
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
                    .clickable { performHaptic(hapticView, hapticOn); onToggleExpand() }
                    .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
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
                                        color = accentColor.copy(
                                            alpha = if (isComplete) 0.4f else pulseAlpha
                                        )
                                    )
                                }
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = headerText,
                            style = MaterialTheme.typography.labelMedium,
                            color = textColor.copy(alpha = AlphaTokens.MUTED)
                        )
                    }

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

                // 文本为空时的流式占位符
                if (isStreaming && text.isBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = accentColor.copy(alpha = AlphaTokens.MUTED)
                    )
                }

                // 可展开内容
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(6.dp))
                        val halfScreenHeight = halfScreenHeight()
                        val reasoningScrollState = rememberScrollState()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = halfScreenHeight)
                                .verticalScroll(reasoningScrollState)
                        ) {
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
