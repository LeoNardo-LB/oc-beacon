package dev.leonardo.ocbeacon.ui.screens.sessions.components

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.ocbeacon.domain.model.SessionCategory
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 用于管理会话分类并把分类分配给会话的对话框。
 *
 * - 列出已有分类；点击某个分类会以它的 id 调用 [onAssign]。
 * - 顶部的"无分类"选项会以 null 调用 [onAssign]，清除分配。
 * - 底部的创建区通过 [onCreateCategory] 构建新分类。
 * - 每个分类行可通过 [onDeleteCategory] 删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionCategoryPickerDialog(
    categories: List<SessionCategory>,
    assignedCategoryId: String?,
    onAssign: (String?) -> Unit,
    onCreateCategory: (name: String, color: String, icon: String) -> Unit,
    onDeleteCategory: (categoryId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val params = amoledDialogParams()
    var newCategoryName by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(SessionCategoryStyle.colorKeys.first()) }
    var selectedIcon by remember { mutableStateOf(SessionCategoryStyle.iconKeys.first()) }

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
                Text(text = "分类", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                // "无分类" — 清除分配
                CategoryOptionRow(
                    icon = Icons.Filled.Close,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    name = "无分类",
                    isSelected = assignedCategoryId == null,
                    onClick = { onAssign(null) },
                )

                categories.forEach { category ->
                    CategoryOptionRow(
                        icon = SessionCategoryStyle.icon(category.icon),
                        tint = SessionCategoryStyle.color(category.color),
                        name = category.name,
                        isSelected = assignedCategoryId == category.id,
                        onClick = { onAssign(category.id) },
                        onDelete = { onDeleteCategory(category.id) },
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // 新建分类区
                Text(
                    text = "新建分类",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    label = { Text("分类名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))

                Text(text = "颜色", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SessionCategoryStyle.colorKeys.forEach { key ->
                        ColorDot(
                            color = SessionCategoryStyle.color(key),
                            isSelected = selectedColor == key,
                            onClick = { selectedColor = key },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                Text(text = "图标", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SessionCategoryStyle.iconKeys.forEach { key ->
                        IconOption(
                            icon = SessionCategoryStyle.icon(key),
                            isSelected = selectedIcon == key,
                            onClick = { selectedIcon = key },
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newCategoryName.isNotBlank()) {
                                onCreateCategory(newCategoryName.trim(), selectedColor, selectedIcon)
                                newCategoryName = ""
                            }
                        },
                        enabled = newCategoryName.isNotBlank(),
                    ) { Text("添加") }
                }
            }
        }
    }
}

@Composable
private fun CategoryOptionRow(
    icon: ImageVector,
    tint: Color,
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SpacingTokens.SM.dp))
            .background(if (isSelected) tint.copy(alpha = AlphaTokens.SELECTED) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = tint,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除分类",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                )
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
