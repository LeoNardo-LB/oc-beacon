package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.PendingMessage
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 拖拽重排进行时状态（堆积 tab 长按拖动）。 */
private data class DragState(val index: Int, val offset: Float)

/**
 * 堆积消息 / TODO 双 tab 面板（2026-08-20 设计定稿；形态照 TaskSheet：
 * ModalBottomSheet + 限高 75% + 无拉杆）。
 *
 * - 堆积 tab：每行右对齐 [编辑 · 删除 · 发送]；长按拖拽排序；标题栏
 *   「继续」（队列非空且会话空闲时发队首 1 条）与「清空」（带确认）；
 *   推送中锁定全部操作，队首行标记发送中。
 * - TODO tab：只读镜像（SSE todo.updated 实时 + REST hydrate），三态符号
 *   对齐 TUI（✓ completed / • in_progress / ○ 其余；cancelled 删除线）。
 *   无能力服务器（V2 beta）由调用方 showTodoTab=false 整体隐藏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingTodoSheet(
    queue: List<PendingMessage>,
    todos: List<SseEvent.TodoUpdated.Todo>,
    showTodoTab: Boolean,
    isSessionIdle: Boolean,
    isDraining: Boolean,
    onContinue: () -> Unit,
    onClear: () -> Unit,
    onEdit: (id: Long, text: String) -> Unit,
    onDelete: (id: Long) -> Unit,
    onSendOne: (id: Long, text: String) -> Unit,
    onReorder: (orderedIds: List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PendingMessage?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = {},
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.75f)
                .padding(bottom = 24.dp)
        ) {
            // 标题栏：标题 + 「继续」（空闲且队列非空）+ 「清空」（队列非空）+ 关闭
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.SM.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.pending_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (queue.isNotEmpty() && isSessionIdle && !isDraining) {
                    TextButton(onClick = onContinue) {
                        Text(stringResource(R.string.pending_continue))
                    }
                }
                if (queue.isNotEmpty()) {
                    TextButton(onClick = { showClearConfirm = true }) {
                        Text(stringResource(R.string.pending_clear))
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(android.R.string.cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val tabCount = if (showTodoTab) 2 else 1
            if (selectedTab >= tabCount) selectedTab = 0
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(stringResource(R.string.pending_tab_stacked, queue.size))
                    },
                )
                if (showTodoTab) {
                    val done = todos.count { it.status == "completed" || it.status == "cancelled" }
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(stringResource(R.string.pending_tab_todo, done, todos.size))
                        },
                    )
                }
            }

            if (selectedTab == 0) {
                StackedList(
                    queue = queue,
                    isDraining = isDraining,
                    onEdit = { editing = it },
                    onDelete = onDelete,
                    onSendOne = onSendOne,
                    onReorder = onReorder,
                )
            } else {
                TodoList(todos = todos)
            }
        }
    }

    // 编辑对话框
    editing?.let { msg ->
        var text by remember(msg.id) { mutableStateOf(msg.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.pending_edit_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 3,
                    maxLines = 8,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (text.isNotBlank()) onEdit(msg.id, text)
                        editing = null
                    },
                ) { Text(stringResource(R.string.pending_item_edit)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    // 清空确认
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.pending_clear_confirm_title)) },
            text = { Text(stringResource(R.string.pending_clear_confirm_text, queue.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onClear()
                    },
                ) { Text(stringResource(R.string.pending_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/** 堆积列表：长按拖拽排序 + 每行 [编辑 · 删除 · 发送]；推送中锁定。 */
@Composable
private fun StackedList(
    queue: List<PendingMessage>,
    isDraining: Boolean,
    onEdit: (PendingMessage) -> Unit,
    onDelete: (id: Long) -> Unit,
    onSendOne: (id: Long, text: String) -> Unit,
    onReorder: (orderedIds: List<Long>) -> Unit,
) {
    if (queue.isEmpty()) {
        EmptyHint(text = stringResource(R.string.pending_empty))
        return
    }
    // 渲染源（2026-08-20 E2E 修复：原「本地镜像 + LaunchedEffect 同步」模式在
    // 删除/编辑后有陈旧窗口——tab 计数已变而列表仍旧值。改为非拖拽时直接渲染
    // queue（Room 更新即时反映零残留），仅拖拽期间使用本地副本）
    var dragOrder by remember { mutableStateOf<List<PendingMessage>?>(null) }
    val order = dragOrder ?: queue
    var drag by remember { mutableStateOf<DragState?>(null) }
    val swapThreshold = with(LocalDensity.current) { 48.dp.toPx() }

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(order, key = { _, item -> item.id }) { index, item ->
            val isDragged = drag?.index == index
            val isHeadSending = isDraining && index == 0
            // 包裹式结构（修复 2026-08-20 初稿 bug：手势 Box 原是 Surface 的
            // 兄弟节点——0 高度不覆盖行内容，长按拖拽永远不命中）：单一 Box
            // 同时承载视觉位移（graphicsLayer/zIndex）、拖拽手势与行内容。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationY = if (isDragged) drag?.offset ?: 0f else 0f }
                    .zIndex(if (isDragged) 1f else 0f)
                    .animateItem()
                    .pointerInput(item.id, isDraining, order.size) {
                        if (isDraining) return@pointerInput
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                // 拖拽开始：快照当前渲染列表为本地副本
                                drag = DragState(index, 0f)
                                dragOrder = order
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                val d = drag ?: return@detectDragGesturesAfterLongPress
                                var newOffset = d.offset + amount.y
                                var newIndex = d.index
                                // 越过一行高度即与相邻行交换（本地即时反馈）
                                val current = dragOrder ?: order
                                while (newOffset > swapThreshold && newIndex < current.size - 1) {
                                    newIndex++
                                    newOffset -= swapThreshold
                                }
                                while (newOffset < -swapThreshold && newIndex > 0) {
                                    newIndex--
                                    newOffset += swapThreshold
                                }
                                newOffset = newOffset.coerceIn(-swapThreshold, swapThreshold)
                                if (newIndex != d.index) {
                                    dragOrder = current.toMutableList().apply {
                                        val moved = removeAt(d.index)
                                        add(newIndex, moved)
                                    }
                                }
                                drag = DragState(newIndex, newOffset)
                            },
                            onDragEnd = {
                                onReorder((dragOrder ?: order).map { it.id })
                                drag = null
                                // 提交后清本地副本——Room 重排发射的 queue 接管渲染
                                dragOrder = null
                            },
                            onDragCancel = {
                                drag = null
                                dragOrder = null
                            },
                        )
                    },
            ) {
                Surface(
                    color = if (isDragged) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    shape = ShapeTokens.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.SM.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.text,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val timeText = remember(item.createdAt) {
                                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAt))
                            }
                            Text(
                                text = if (isHeadSending) {
                                    stringResource(R.string.pending_item_sending)
                                } else {
                                    timeText
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isHeadSending) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                                },
                            )
                        }
                        IconButton(onClick = { onEdit(item) }, enabled = !isDraining, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.pending_item_edit),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                            )
                        }
                        IconButton(onClick = { onDelete(item.id) }, enabled = !isDraining, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.pending_item_delete),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                            )
                        }
                        IconButton(
                            onClick = { onSendOne(item.id, item.text) },
                            enabled = !isDraining,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.pending_item_send),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** TODO 只读列表：三态符号（✓/•/○），cancelled 删除线。 */
@Composable
private fun TodoList(todos: List<SseEvent.TodoUpdated.Todo>) {
    if (todos.isEmpty()) {
        EmptyHint(text = stringResource(R.string.todo_empty))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(todos, key = { _, it -> it.content + "#" + it.status }) { _, todo ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.XS.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
            ) {
                when (todo.status) {
                    "completed" -> Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                    )
                    "in_progress" -> Icon(
                        Icons.Default.FiberManualRecord,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    else -> Icon(
                        Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                    )
                }
                Text(
                    text = todo.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (todo.status) {
                        "completed" -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                        "in_progress" -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM)
                    },
                    textDecoration = if (todo.status == "cancelled") TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SpacingTokens.XXL.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
        )
    }
}
