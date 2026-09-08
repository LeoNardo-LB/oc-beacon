package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.screens.chat.SheetScaffold
import dev.leonardo.ocbeacon.domain.model.QueuedInboxItem
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 排队队列 sheet（#313，2026-09-03 用户「能力→容器」映射裁决）——队列面板进
 * FAB 菜单体系（与 TODO/AGENT/GOAL/SHELL 同款：FAB 入口 → 自有 sheet），
 * 取代 2026-09-01 Task 4 的输入条上方 QueueDock 条（对 DSH Web dock 布局的
 * 照搬，退役）。
 *
 * 行为沿 QueueDock 全量迁移（#356 扩 V2）：
 * - 仅 queued placement 项（调用方/VM 已过滤）；空队列给空态文案；
 * - 每条 preview + 动作：编辑（纯文本 text != null 时）/删除/steer（运行中）；
 * - [editSupported]=false（V2 inbox 无 edit 动词——OpenAPI 仅 remove/steer
 *   转换）隐藏编辑入口；
 * - 子代理会话只读（[isReadOnly]）——隐藏全部动作，仅预览；
 * - steer 仅 running + next-turn 有效：按钮按 [isRunning] 启用，服务器
 *   steer-unavailable 时经 VM 弹专属提示（本组件不直接感知）；
 * - 编辑态：OutlinedTextField 单行 + 保存/取消；队列快照变化后编辑条目消失 → 退出编辑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    items: List<QueuedInboxItem>,
    isRunning: Boolean,
    isReadOnly: Boolean,
    editSupported: Boolean,
    onDismiss: () -> Unit,
    onSaveEdit: (itemId: String, text: String) -> Unit,
    onRemove: (itemId: String) -> Unit,
    onSteer: (itemId: String) -> Unit,
) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf("") }

    // 队列快照变化后编辑条目消失 → 退出编辑
    LaunchedEffect(items) {
        if (editingId != null && items.none { it.id == editingId }) {
            editingId = null
        }
    }

    SheetScaffold(
        title = stringResource(R.string.queue_title) + " (" + items.size + ")",
        onDismiss = onDismiss,
    ) {
        if (items.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(SpacingTokens.LG.dp)) {
                Text(
                    text = stringResource(R.string.queue_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                )
            }
            return@SheetScaffold
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            items.forEach { item ->
                val isEditing = editingId == item.id
                QueueRow(
                    item = item,
                    isRunning = isRunning,
                    isReadOnly = isReadOnly,
                    editSupported = editSupported,
                    editing = isEditing,
                    editingText = if (isEditing) editingText else item.text.orEmpty(),
                    onEditingTextChange = { editingText = it },
                    onStartEdit = {
                        editingId = item.id
                        editingText = item.text.orEmpty()
                    },
                    onSaveEdit = {
                        if (editingText.isNotBlank()) {
                            onSaveEdit(item.id, editingText)
                            editingId = null
                        }
                    },
                    onCancelEdit = { editingId = null },
                    onRemove = { onRemove(item.id) },
                    onSteer = { onSteer(item.id) },
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: QueuedInboxItem,
    isRunning: Boolean,
    isReadOnly: Boolean,
    editSupported: Boolean,
    editing: Boolean,
    editingText: String,
    onEditingTextChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onRemove: () -> Unit,
    onSteer: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SpacingTokens.XS.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editing) {
            OutlinedTextField(
                value = editingText,
                onValueChange = onEditingTextChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text(stringResource(R.string.queue_edit_hint)) },
            )
            IconButton(onClick = onSaveEdit, enabled = editingText.isNotBlank()) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.server_save),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onCancelEdit) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
            }
        } else {
            Text(
                text = item.preview.ifBlank { item.id },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = SpacingTokens.SM.dp),
            )
            if (!isReadOnly) {
                val actionTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM)
                // 编辑仅纯文本可用（text != null）且服务器有 edit 动词
                //（#356：V2 inbox 仅 remove/steer → editSupported=false 禁用）
                IconButton(onClick = onStartEdit, enabled = editSupported && item.text != null) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.queue_edit),
                        tint = actionTint,
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.queue_remove), tint = actionTint)
                }
                IconButton(onClick = onSteer, enabled = isRunning) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.queue_steer),
                        tint = if (isRunning) MaterialTheme.colorScheme.primary else actionTint,
                    )
                }
            }
        }
    }
}
