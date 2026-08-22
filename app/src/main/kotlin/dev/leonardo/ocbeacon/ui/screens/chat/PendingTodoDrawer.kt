package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import kotlin.math.abs
import kotlinx.coroutines.launch

// ============ 常驻抽屉锚点（2026-08-22 设计定案：主对话流模块内覆盖式抽屉） ============

/** 抽屉档位索引（收起=仅拉手 / 半开 20% / 展开 80%）。 */
internal object PendingDrawerAnchors {
    const val SNAP_COLLAPSED = 0
    const val SNAP_MID = 1
    const val SNAP_FULL = 2
    val SNAP_COUNT = 3

    /** 收起态高度 = 拉手行高（2026-08-22 用户复改：只露拉手，约为旧标题栏 48dp 的 1/3）。 */
    val HANDLE_HEIGHT = 16.dp

    /** 标题栏（segment + 操作钮）行高——展开后位于拉手下方。 */
    val HEADER_HEIGHT = 48.dp

    /** 各档位占主对话流模块高度的比例（index 0 用 HANDLE_HEIGHT，不用比例）。 */
    val FRACTIONS = floatArrayOf(0f, 0.20f, 0.80f)

    /** 档位像素锚点（handlePx 用固定高度，其余按容器高比例）。 */
    fun anchorsPx(containerHeightPx: Float, handlePx: Float): FloatArray =
        FloatArray(SNAP_COUNT) { i ->
            if (i == SNAP_COLLAPSED) handlePx else containerHeightPx * FRACTIONS[i]
        }
}

/** segment 标签（Q3）：文字 + 数量 Badge 角标（count=0 只显文字——段本身已置灰）。 */
@Composable
private fun SegLabel(text: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        if (count > 0) {
            Badge { Text(text = count.coerceAtMost(99).toString(), style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/** 拖拽释放后吸附到最近锚点（纯函数，单测目标）。 */
internal fun nearestSnapIndex(currentPx: Float, anchorsPx: FloatArray): Int {
    var best = 0
    var bestDist = abs(anchorsPx[0] - currentPx)
    for (i in 1 until anchorsPx.size) {
        val d = abs(anchorsPx[i] - currentPx)
        if (d < bestDist) {
            best = i; bestDist = d
        }
    }
    return best
}

/** 抽屉可见性（纯函数，单测目标）：堆积非空，或 TODO 段有数据。 */
internal fun pendingDrawerVisible(queueSize: Int, todosSize: Int, todoCapable: Boolean): Boolean =
    queueSize > 0 || (todoCapable && todosSize > 0)

/** 每会话抽屉记忆（内存级——App 存活期内跨会话切换保留，重启回收起）。 */
internal data class PendingDrawerMemory(
    val snap: Int = PendingDrawerAnchors.SNAP_COLLAPSED,
    val segment: Int = 0,
)

/** 抽屉状态存储（顶层快照状态——任意组合可观察，无需经 ViewModel）。 */
internal object PendingDrawerMemoryStore {
    val states = androidx.compose.runtime.mutableStateMapOf<String, PendingDrawerMemory>()
}

/**
 * 堆积/TODO 主对话抽屉（2026-08-22 设计定案，grilling Q4-Q17）。
 *
 * - 覆盖式：悬浮在消息流模块底部（锚定底边、贴输入组件上沿），不挤压消息布局；
 *   消息列表 contentPadding 由调用方按 [bottomOverlayInset] 补偿。
 * - 三档吸附：收起（= 仅拉手 16dp，2026-08-22 用户复改）/ 20% / 80%（容器 =
 *   主对话流模块）；拉手+标题栏空白可拖（整栏语义），收起只靠拖（点 segment =
 *   展开到 20%）。
 * - 拉手：Material 抽屉标准小横条（32x4dp 居中）——收起态唯一可见物。
 * - 标题栏（展开后位于拉手下方）：左 segment 双段占 1/2 宽（堆积 N /
 *   TODO n/m，无数据整段不显示）；右纯图标 ▶继续 + 🗑清空（TODO 段无操作钮）。
 * - 双空完全隐藏；键盘弹起自动收起（收键盘不恢复）；流式输出不打扰。
 * - 堆积行紧凑化 ~44dp（IconButton 28dp + XS 竖向 padding）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PendingTodoDrawer(
    sessionId: String,
    /** 主对话流模块高度（px）——抽屉与 inset 计算的共同容器。 */
    containerHeightPx: Float,
    queue: List<PendingMessage>,
    todos: List<SseEvent.TodoUpdated.Todo>,
    showTodoSegment: Boolean,
    isSessionIdle: Boolean,
    isDraining: Boolean,
    /** 键盘可见（Q8：弹起自动收起，收起后不自动恢复）。 */
    imeVisible: Boolean,
    onContinue: () -> Unit,
    onClear: () -> Unit,
    onEdit: (id: Long, text: String) -> Unit,
    onDelete: (id: Long) -> Unit,
    onSendOne: (id: Long, text: String) -> Unit,
    onReorder: (orderedIds: List<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 双空完全隐藏（Q11）——TODO 段仅在有能力且有数据时计入
    if (!pendingDrawerVisible(queue.size, todos.size, showTodoSegment)) return
    if (containerHeightPx <= 0f) return
    val todoSegmentVisible = showTodoSegment && todos.isNotEmpty()

    val density = LocalDensity.current
    val handlePx = with(density) { PendingDrawerAnchors.HANDLE_HEIGHT.toPx() }
    val anchors = PendingDrawerAnchors.anchorsPx(containerHeightPx, handlePx)

    val memory = PendingDrawerMemoryStore.states[sessionId] ?: PendingDrawerMemory()
    val snap = memory.snap.coerceIn(0, PendingDrawerAnchors.SNAP_COUNT - 1)
    // 队列空时堆积段无内容——自动落 TODO 段（记忆不回写，队列恢复时还原）
    val segment = if (queue.isEmpty() && todoSegmentVisible) 1 else memory.segment
    val scope = rememberCoroutineScope()

    fun setMemory(newSnap: Int, newSegment: Int) {
        PendingDrawerMemoryStore.states[sessionId] = PendingDrawerMemory(newSnap, newSegment)
    }

    val height = remember { Animatable(anchors[snap]) }
    // 外部状态变化（键盘收起/点 segment/容器尺寸变化）→ 动画到锚点。
    // 2026-08-22 bug 修复（Q5「能停在自定义高度」根因）：原 key 含 FloatArray
    // （引用比较恒不等）→ 每次重组重启 animateTo 与拖拽 snapTo 竞争，释放后
    // 停在两者对抗的中间位置。key 改稳定标量（anchors 内容由这两值决定）。
    LaunchedEffect(snap, containerHeightPx) {
        height.animateTo(anchors[snap])
    }
    // 键盘弹起 → 自动收起（Q8）
    LaunchedEffect(imeVisible) {
        if (imeVisible && PendingDrawerMemoryStore.states[sessionId]?.snap != PendingDrawerAnchors.SNAP_COLLAPSED) {
            setMemory(PendingDrawerAnchors.SNAP_COLLAPSED, segment)
        }
    }

    var showClearConfirm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PendingMessage?>(null) }

    fun toggleSegment(target: Int) {
        // Q15：点 segment = 展开到 30%（若已展开保持档位），再点当前段不收起
        setMemory(maxOf(snap, PendingDrawerAnchors.SNAP_MID), target)
    }

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        // 2026-08-22 用户复改：底部抽屉标准圆角——顶部两角 16dp、底部直角（贴输入栏）
        shape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(with(density) { height.value.toDp() }),
    ) {
        // 手势挂整列（Q16「整栏可拖」）：列表/segment/按钮各自消费的事件不冒泡，
        // 拉手与标题栏空白处冒泡到此 → 拖拽高度；收起态（16dp）拉手即把手。
        // clipToBounds：收起态只露拉手（标题栏/列表裁掉）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
                .pointerInput(anchors[0], anchors[1], anchors[2]) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            val target = (height.value - amount).coerceIn(anchors[0], anchors[2])
                            scope.launch { height.snapTo(target) }
                        },
                        onDragEnd = {
                            val nearest = nearestSnapIndex(height.value, anchors)
                            if (nearest != snap) setMemory(nearest, segment)
                            else scope.launch { height.animateTo(anchors[nearest]) }
                        },
                    )
                },
        ) {
            // ===== 拉手（2026-08-22 用户复改：Material 抽屉标准样式小横条；收起态唯一可见物） =====
            // 点击=切换收起/半开（E2E 实证：44px 拖拽越过上界即被消息列表滚动接管——
            // 小拉手拖拽不可靠，点击是标准抽屉交互兜底；拖拽仍保留（展开态区域大））
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // requiredHeight（Q4 修复）：收起动画中 Surface 约束压缩到 16dp，
                    // 普通 height 会被 coerce 压缩导致内容缩小；required 保持原高
                    // 溢出被 clipToBounds 裁掉（标准抽屉「裁剪不压缩」行为）
                    .requiredHeight(PendingDrawerAnchors.HANDLE_HEIGHT)
                    .pointerInput(snap, segment) {
                        detectTapGestures(
                            onTap = {
                                setMemory(
                                    if (snap == PendingDrawerAnchors.SNAP_COLLAPSED) PendingDrawerAnchors.SNAP_MID
                                    else PendingDrawerAnchors.SNAP_COLLAPSED,
                                    segment,
                                )
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = 4.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                        )
                )
            }
            // ===== 标题栏（segment 左 1/2 + 右端操作钮；展开后位于拉手下方） =====
            // requiredHeight（Q4）：保持 48dp 原高被裁剪，不随收起动画压缩缩小
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .requiredHeight(PendingDrawerAnchors.HEADER_HEIGHT)
                    .padding(horizontal = SpacingTokens.MD.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
            ) {
                // segment 左 1/2 + 紧凑高度（Q1：默认最小高 40dp 撑大标题栏 → 32dp）
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(32.dp),
                ) {
                    // Q3：双段恒展示——无数据置灰（enabled=false）；角标=数量 Badge
                    SegmentedButton(
                        selected = segment == 0,
                        onClick = { toggleSegment(0) },
                        enabled = queue.isNotEmpty(),
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        label = {
                            SegLabel(
                                text = stringResource(R.string.pending_tab_stacked_plain),
                                count = queue.size,
                            )
                        },
                    )
                    if (showTodoSegment) {
                        val pending = todos.count { it.status == "pending" || it.status == "in_progress" }
                        SegmentedButton(
                            selected = segment == 1,
                            onClick = { toggleSegment(1) },
                            enabled = todos.isNotEmpty(),
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            label = {
                                SegLabel(
                                    text = stringResource(R.string.pending_tab_todo_plain),
                                    count = pending,
                                )
                            },
                        )
                    }
                }
                // Q2：右侧操作钮推到行尾（Spacer 吃掉中间全部余量）
                Spacer(modifier = Modifier.weight(1f))
                if (segment == 0 && queue.isNotEmpty()) {
                    if (isSessionIdle && !isDraining) {
                        IconButton(onClick = onContinue, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.pending_continue),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.pending_clear),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                        )
                    }
                }
            }

            // ===== 内容区（收起时零高度不可见；展开时独立滚动——Q14） =====
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (segment == 0) {
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
    }

    // 编辑对话框（自 PendingTodoSheet 迁移）
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

    // 清空确认（自 PendingTodoSheet 迁移）
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

/** 堆积列表：长按拖拽排序 + 每行 [编辑 · 删除 · 发送]；推送中锁定（自 PendingTodoSheet 迁移 + 2026-08-22 紧凑化 44dp）。 */
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
    // 渲染源（2026-08-20 E2E 修复）：非拖拽时直接渲染 queue（Room 即时），仅拖拽期间本地副本
    var dragOrder by remember { mutableStateOf<List<PendingMessage>?>(null) }
    val order = dragOrder ?: queue
    var drag by remember { mutableStateOf<DragState?>(null) }
    val swapThreshold = with(LocalDensity.current) { 44.dp.toPx() }

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(order, key = { _, item -> item.id }) { index, item ->
            val isDragged = drag?.index == index
            val isHeadSending = isDraining && index == 0
            // 包裹式结构（2026-08-20 修复）：单一 Box 承载视觉位移/拖拽手势/行内容
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
                                drag = DragState(index, 0f)
                                dragOrder = order
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                val d = drag ?: return@detectDragGesturesAfterLongPress
                                var newOffset = d.offset + amount.y
                                var newIndex = d.index
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
                    color = if (isDragged) MaterialTheme.colorScheme.surfaceContainerHigh
                    else MaterialTheme.colorScheme.surface,
                    shape = ShapeTokens.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 2026-08-22 紧凑化：竖向 SM→XS + 行内按钮 32→28（~44dp）
                            .padding(horizontal = SpacingTokens.MD.dp, vertical = SpacingTokens.XS.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
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
                        IconButton(onClick = { onEdit(item) }, enabled = !isDraining, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.pending_item_edit),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                            )
                        }
                        IconButton(onClick = { onDelete(item.id) }, enabled = !isDraining, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.pending_item_delete),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                            )
                        }
                        IconButton(
                            onClick = { onSendOne(item.id, item.text) },
                            enabled = !isDraining,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.pending_item_send),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** TODO 只读列表：三态符号（✓/•/○），cancelled 删除线（自 PendingTodoSheet 迁移）。 */
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
                    .padding(horizontal = SpacingTokens.MD.dp, vertical = SpacingTokens.XS.dp),
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

/** 空态提示（自 PendingTodoSheet 迁移）。 */
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

/** 拖拽重排进行时状态（堆积段长按拖动；自 PendingTodoSheet 迁移）。 */
private data class DragState(val index: Int, val offset: Float)

// detectDragGesturesAfterLongPress import（StackedList 拖拽重排）
