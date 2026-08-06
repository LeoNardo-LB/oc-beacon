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
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 多选标签分配对话框。
 *
 * - 列出已有用户标签，复选框多选；本地 [selected] 状态保存勾选集合。
 * - 底部"新建标签"区通过 [onCreateTag] 创建标签，返回新标签 id 后自动勾选。
 * - 点确定以最终勾选集合调用 [onConfirm]；点关闭调用 [onDismiss]。
 *
 * 视觉上与历史分类选择器保持一致：颜色/图标选择器复用
 * [SessionCategoryStyle] + 同样的 ColorDot / IconOption 私有组件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagPickerDialog(
    tags: List<Tag>,
    selectedTagIds: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    onCreateTag: (name: String, color: String, icon: String) -> String,
) {
    val params = amoledDialogParams()
    var selected by remember { mutableStateOf(selectedTagIds) }
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
                Text(text = stringResource(R.string.category), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                tags.forEach { tag ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(SpacingTokens.SM.dp))
                            .clickable {
                                selected = if (tag.id in selected) selected - tag.id else selected + tag.id
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = tag.id in selected,
                            onCheckedChange = {
                                selected = if (tag.id in selected) selected - tag.id else selected + tag.id
                            },
                        )
                        Icon(
                            imageVector = SessionCategoryStyle.icon(tag.icon),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = SessionCategoryStyle.color(tag.color),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = tag.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // 新建标签区
                Text(
                    text = stringResource(R.string.new_category),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    label = { Text(stringResource(R.string.category_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))

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
                            isSelected = selectedColor == key,
                            onClick = { selectedColor = key },
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
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newCategoryName.isNotBlank()) {
                                val newId = onCreateTag(newCategoryName.trim(), selectedColor, selectedIcon)
                                selected = selected + newId
                                newCategoryName = ""
                            }
                        },
                        enabled = newCategoryName.isNotBlank(),
                    ) { Text(stringResource(R.string.add)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.ok)) }
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
