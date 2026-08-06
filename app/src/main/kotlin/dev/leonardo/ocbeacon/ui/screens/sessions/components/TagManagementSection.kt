package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 设置页"标签管理"区块。
 *
 * - 顶层可折叠区块（标题 + 箭头），样式与 [ServerSettingsContent] 的 MCP 区块一致。
 * - 列出全部 USER 标签：图标 + 名称 + 关联会话数 + 编辑/删除按钮；点击行展开关联会话列表。
 * - 关联会话列表：标题（回退到 sessionId 前 12 字符）+ 逐会话"解除标签"按钮。
 * - 新建/编辑通过 [TagEditDialog]；删除前弹 [AlertDialog] 二次确认。
 *
 * @param tags USER 标签列表
 * @param tagAssignments sessionId → tagIds（含内置收藏标签）
 * @param sessions 本服务器会话（标题查找用）
 */
@Composable
fun TagManagementSection(
    tags: List<Tag>,
    tagAssignments: Map<String, List<String>>,
    sessions: List<Session>,
    onAddTag: (Tag) -> Unit,
    onUpdateTag: (Tag) -> Unit,
    onDeleteTag: (String) -> Unit,
    onRemoveAssignment: (sessionId: String, tagId: String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var expandedTagId by remember { mutableStateOf<String?>(null) }
    var editingTag by remember { mutableStateOf<Tag?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deletingTag by remember { mutableStateOf<Tag?>(null) }

    Column {
        // 区块标题（可点击展开/收起，样式同 MCP 区块）
        SettingsSectionHeader(
            title = stringResource(R.string.tag_management_title),
            expanded = expanded,
            onClick = { expanded = !expanded },
            trailing = {
                Text(
                    "(${tags.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
        )

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(Modifier.fillMaxWidth()) {
                if (tags.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_category),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { creating = true }) {
                        Text(stringResource(R.string.new_tag))
                    }
                }

                tags.forEach { tag ->
                    val sessionCount = tagAssignments.values.count { tag.id in it }
                    SettingsListRow(
                        leading = {
                            Icon(
                                imageVector = SessionCategoryStyle.icon(tag.icon),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = SessionCategoryStyle.color(tag.color),
                            )
                        },
                        title = tag.name,
                        subtitle = "($sessionCount)",
                        trailing = {
                            IconButton(
                                onClick = { editingTag = tag },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = stringResource(R.string.edit),
                                    Modifier.size(16.dp),
                                )
                            }
                            IconButton(
                                onClick = { deletingTag = tag },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                    Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                        onClick = {
                            expandedTagId = if (expandedTagId == tag.id) null else tag.id
                        },
                    )

                    // 展开：关联会话列表（标题 + 解除按钮）
                    if (expandedTagId == tag.id) {
                        val assignedSessionIds = tagAssignments
                            .filterValues { tag.id in it }
                            .keys
                        if (assignedSessionIds.isEmpty()) {
                            Text(
                                text = stringResource(R.string.no_sessions_with_tag),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 32.dp, end = 16.dp, bottom = 4.dp),
                            )
                        } else {
                            assignedSessionIds.forEach { sessionId ->
                                val session = sessions.firstOrNull { it.id == sessionId }
                                SettingsListRow(
                                    modifier = Modifier.padding(start = 32.dp),
                                    leading = {
                                        Icon(
                                            imageVector = Icons.Outlined.ChatBubbleOutline,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    title = session?.title ?: sessionId.take(12),
                                    trailing = {
                                        TextButton(onClick = { onRemoveAssignment(sessionId, tag.id) }) {
                                            Text(stringResource(R.string.remove_tag))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 新建/编辑 dialog
    if (creating || editingTag != null) {
        TagEditDialog(
            initial = editingTag,
            onDismiss = { creating = false; editingTag = null },
            onSave = { name, color, icon ->
                if (editingTag != null) {
                    onUpdateTag(editingTag!!.copy(name = name, color = color, icon = icon))
                } else {
                    onAddTag(
                        Tag(
                            id = "tag_${System.currentTimeMillis()}",
                            name = name,
                            color = color,
                            icon = icon,
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                }
                creating = false; editingTag = null
            },
        )
    }

    // 删除确认 dialog
    deletingTag?.let { tag ->
        AlertDialog(
            onDismissRequest = { deletingTag = null },
            title = { Text(stringResource(R.string.delete_tag_title)) },
            text = { Text(stringResource(R.string.delete_tag_message, tag.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteTag(tag.id)
                    deletingTag = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingTag = null }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagEditDialog(
    initial: Tag?,
    onDismiss: () -> Unit,
    onSave: (name: String, color: String, icon: String) -> Unit,
) {
    val params = amoledDialogParams()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var color by remember { mutableStateOf(initial?.color ?: SessionCategoryStyle.colorKeys.first()) }
    var icon by remember { mutableStateOf(initial?.icon ?: SessionCategoryStyle.iconKeys.first()) }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(if (initial == null) R.string.new_tag else R.string.edit_tag),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.category_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                Text(text = stringResource(R.string.color), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SessionCategoryStyle.colorKeys.forEach { key ->
                        ColorDot(
                            color = SessionCategoryStyle.color(key),
                            isSelected = color == key,
                            onClick = { color = key },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                Text(text = stringResource(R.string.icon), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SessionCategoryStyle.iconKeys.forEach { key ->
                        IconOption(
                            icon = SessionCategoryStyle.icon(key),
                            isSelected = icon == key,
                            onClick = { icon = key },
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { if (name.isNotBlank()) onSave(name.trim(), color, icon) },
                        enabled = name.isNotBlank(),
                    ) { Text(stringResource(R.string.ok)) }
                }
            }
        }
    }
}

@Composable
private fun ColorDot(color: Color, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = AlphaTokens.HIGH))
            .border(
                width = if (isSelected) 3.dp else 0.dp,
                color = MaterialTheme.colorScheme.onSurface,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

@Composable
private fun IconOption(icon: ImageVector, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(SpacingTokens.SM.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.SELECTED)
                else Color.Transparent
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
