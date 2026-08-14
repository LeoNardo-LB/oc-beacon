package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.ui.screens.chat.util.JumpTarget
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import dev.leonardo.ocbeacon.util.DateFormatters
import java.util.Date

/**
 * 列出所有用户提问以便快速导航的对话框。
 *
 * @param show 对话框是否可见
 * @param jumpTargets 提取的用户提问（参见 JumpTargetExtractor；数据源为 Room 全量 user 消息）
 * @param currentMsgId 当前可见问题的 msgId，用于高亮；null = 无
 * @param anchorTimestampMs 当前可见区域时间锚点（ms），currentMsgId 匹配不到时
 *   降级定位到时间最近的问题；null = 无锚点（降级到最新项）
 * @param isLoading jumpTargets 正在异步加载（Room 查询期间显示 loading 指示）
 * @param onJump 用户点击某个提问时以 msgId 调用
 * @param onDismiss 对话框应关闭时调用
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickNavigateSheet(
    show: Boolean,
    jumpTargets: List<JumpTarget>,
    currentMsgId: String?,
    anchorTimestampMs: Long? = null,
    isLoading: Boolean = false,
    onJump: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return

    val listState = rememberLazyListState()

    // 对话框打开时自动滚动到当前高亮的提问，
    // 让用户看到自己所在位置而不是 Q1。
    // 2026-08-12 修复迭代：
    // 1. key 加 jumpTargets.size——jumpTargets 异步加载，打开瞬间为空。
    // 2. currentMsgId 匹配不到时降级：先按 anchorTimestampMs（当前可见区域时间锚点）
    //    定位到时间最近的问题；无锚点才回退列表最新项——主会话最新消息常为
    //    长 assistant 回复/空壳（无文本 user 不在列表），currentMsgId=null 时
    //    应定位到"当前位置附近"而非最新（用户反馈"没有定位到当前所在位置"）。
    // 3. 2026-08-12 再修复：key **不含** currentMsgId/anchorTimestampMs——
    //    SSE 流式期间 displayItems 每 48ms 变化 → 锚点持续重算（logcat 实证
    //    findCurrent 每 ~50ms 刷屏）→ 本 effect 反复重启 → 抽屉列表反复
    //    scrollToItem → 用户点击 Q 时条目正在移动 → 点击落空（用户反馈
    //    "选中了之后没有挪动"）。只在打开/列表加载完成时定位一次。
    LaunchedEffect(show, jumpTargets.size) {
        if (jumpTargets.isEmpty()) return@LaunchedEffect
        // 2026-08-12 修复：等一帧布局完成再 scrollToItem——jumpTargets.size 变化
        // 触发本 effect 时 LazyColumn 可能尚未布局新列表，scrollToItem 无效
        //（实测 scroll=11 但视口停在 Q4——定位失效）
        withFrameNanos { }
        val targetIndex = currentMsgId?.let { id -> jumpTargets.indexOfFirst { it.msgId == id } } ?: -1
        val scrollIndex = when {
            targetIndex >= 0 -> targetIndex
            anchorTimestampMs != null -> {
                // 时间锚点：找 timestampMs 最接近的 item
                jumpTargets.minByOrNull { kotlin.math.abs(it.timestampMs - anchorTimestampMs) }
                    ?.let { jumpTargets.indexOf(it) } ?: jumpTargets.lastIndex
            }
            else -> jumpTargets.lastIndex
        }
        if (BuildConfig.DEBUG) {
            AppLogger.d("QuickNavigate", "scroll-to: current=${currentMsgId?.take(12)} anchor=$anchorTimestampMs targets=${jumpTargets.size} match=$targetIndex scroll=$scrollIndex")
        }
        listState.scrollToItem(scrollIndex)
    }

    // 2026-08-12 用户要求：快速导航改为抽屉形式（ModalBottomSheet，与后台面板/模型选择一致）
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = {},
        shape = ShapeTokens.large,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 与后台面板/模型选择统一：30%-75% 屏高
                .heightIn(
                    min = LocalConfiguration.current.screenHeightDp.dp * 0.3f,
                    max = LocalConfiguration.current.screenHeightDp.dp * 0.75f
                )
                .navigationBarsPadding()
        ) {
            // 头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.SM.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.quick_navigate),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                }
            }

            if (jumpTargets.isEmpty()) {
                if (isLoading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = SpacingTokens.XXL.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(SpacingTokens.SM.dp))
                        Text(
                            text = stringResource(R.string.loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.no_questions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = SpacingTokens.XXL.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                return@Column
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = SpacingTokens.XXL.dp),
            ) {
                items(jumpTargets, key = { it.msgId }) { target ->
                    JumpTargetRow(
                        target = target,
                        isCurrent = target.msgId == currentMsgId,
                        onClick = { onJump(target.msgId) },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
                    )
                }
            }
        }
    }
}

@Composable
private fun JumpTargetRow(
    target: JumpTarget,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val timeText = remember(target.timestampMs) {
        DateFormatters.monthDayHourMinute().format(Date(target.timestampMs))
    }
    val highlightBg = if (isCurrent) {
        MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.SELECTED)
    } else {
        Color.Transparent
    }
    val accent = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isCurrent) Modifier.drawBehind {
                    val w = 4.dp.toPx()
                    drawRect(
                        color = accent,
                        topLeft = Offset(0f, 0f),
                        size = Size(w, size.height),
                    )
                } else Modifier
            )
            .background(highlightBg)
            .clickable(onClick = onClick)
            .padding(
                start = if (isCurrent) SpacingTokens.MD.dp else SpacingTokens.LG.dp,
                end = SpacingTokens.LG.dp,
                top = SpacingTokens.MD.dp,
                bottom = SpacingTokens.MD.dp,
            ),
    ) {
        // 第 1 行：Q 标签 + 时间戳
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = target.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isCurrent) accent
                    else MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.HIGH),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(SpacingTokens.SM.dp))
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
            )
        }
        Spacer(Modifier.height(SpacingTokens.XS.dp))
        // 第 2 行：预览，可水平滚动（不截断）
        Text(
            text = target.preview,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.HIGH),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}
