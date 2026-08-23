package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
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
 * - 标签列表以 [FilterChip] 流式布局（[FlowRow]）展示，点击切换选中态；
 *   chip 颜色取自标签自身配色（浅背景/选中加深/实体边框）。
 * - 底部"新增 Tag"按钮内联展开创建表单（名称 + 颜色 + 图标），
 *   通过 [onCreateTag] 创建后返回 id 并自动勾选。
 * - 标签为空时显示占位提示。
 * - 点确定以最终勾选集合调用 [onConfirm]；点关闭调用 [onDismiss]。
 *
 * 视觉上与历史分类选择器保持一致：颜色/图标选择器复用
 * [SessionCategoryStyle] + 同样的 ColorDot / IconOption 私有组件。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagPickerDialog(
    tags: List<Tag>,
    selectedTagIds: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    onCreateTag: (name: String, color: String, icon: String) -> String,
) {
    val params = amoledDialogParams()
    // #115（D2-L25）：选中标签 saveable（Set<String> 可自动保存）
    var selected by rememberSaveable { mutableStateOf(selectedTagIds) }
    // #115（D2-L25）：新分类名输入 saveable
    var newCategoryName by rememberSaveable { mutableStateOf("") }
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
                    .heightIn(max = 560.dp),
            ) {
                Text(text = stringResource(R.string.add_tag), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                // 标签列表区：weight 填充中间空白 + minHeight
                // 标签列表左上对齐；空状态占位垂直居中
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .heightIn(min = 160.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (tags.isEmpty()) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Label,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.no_tags_placeholder),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT),
                            )
                        }
                    } else {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
                            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
                        ) {
                            tags.forEach { tag ->
                                TagChip(
                                    tag = tag,
                                    selected = tag.id in selected,
                                    onClick = {
                                        selected = if (tag.id in selected) selected - tag.id else selected + tag.id
                                    },
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = SpacingTokens.MD.dp))

                // 新建标签表单（常显）：名称 + 颜色 + 图标
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    label = { Text(stringResource(R.string.tag_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))

                Text(text = stringResource(R.string.color), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
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
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
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
                Spacer(Modifier.height(12.dp))

                // 操作栏：关闭 / 添加-确定（名称非空显示"添加"创建并自动勾选，否则"确定"提交）
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                    Spacer(Modifier.width(8.dp))
                    if (newCategoryName.isNotBlank()) {
                        Button(
                            onClick = {
                                val newId = onCreateTag(newCategoryName.trim(), selectedColor, selectedIcon)
                                selected = selected + newId
                                newCategoryName = ""
                            },
                        ) { Text(stringResource(R.string.add)) }
                    } else {
                        Button(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.ok)) }
                    }
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
