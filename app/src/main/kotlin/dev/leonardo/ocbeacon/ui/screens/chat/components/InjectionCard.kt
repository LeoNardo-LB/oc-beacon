package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import dev.leonardo.ocbeacon.util.DateFormatters
import androidx.compose.ui.graphics.Color

/**
 * #416（2026-09-18）：上下文注入卡的**淡形态**——与完整事件卡（[EventCard]：
 * 透明底 + 1dp 描边）分层：注入是宿主喂给模型的上下文，不是对用户的通知，
 * 降级为「左侧色条 + 弱底」（容器语言与 [ReasoningBlock] 同族），不描边。
 *
 * #415：图标 = `DataObject`（{} 上下文语义），区别于 V2 通知卡的铃铛/信息类。
 * 交互与 EventCard 对齐：本体点击 = 展开/收起唯一入口；展开态记忆沿用调用方
 * 的屏幕级 expandedStates（#227 模式）；正文 heightIn(240dp) 内部滚动。
 */
@Composable
internal fun InjectionCard(
    eventKey: String,
    timeMs: Long,
    label: String,
    bodyText: String,
    expandedStates: MutableMap<String, Boolean>,
    modifier: Modifier = Modifier,
) {
    val expanded = expandedStates[eventKey] ?: false
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM)

    Surface(
        shape = ShapeTokens.smallMedium,
        // 2026-09-20 单行形态裁决(Q1 ok):注入卡去容器(#416 淡形态进一步收敛)
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth(),
    ) {
        // 2026-09-20 单行形态:色条移除(与 ReasoningBlock 同步——DSH 无此元素)
        Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = SpacingTokens.XS.dp,
                        end = 10.dp,
                        top = SpacingTokens.XS.dp,
                        bottom = SpacingTokens.XS.dp,
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expandedStates[eventKey] = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.DataObject,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = remember(timeMs) { DateFormatters.messageTimestamp(timeMs) },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                    )
                }
                CardExpandReveal(
                    visible = expanded,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .clipToBounds()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 6.dp),
                    ) {
                        Text(
                            text = bodyText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                        )
                    }
                }
            }
    }
}
