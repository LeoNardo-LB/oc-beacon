package dev.leonardo.ocbeacon.ui.screens.chat.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.ModelCatalog
import dev.leonardo.ocbeacon.domain.model.ProviderCatalog
import dev.leonardo.ocbeacon.ui.components.ProviderIcon
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerDialog(
    providers: List<ProviderCatalog>,
    selectedProviderId: String?,
    selectedModelId: String?,
    onSelect: (providerId: String, modelId: String) -> Unit,
    onDismiss: () -> Unit,
    /** 2026-08-16（方案 A·默认模型）：当前本地默认模型（"pid|mid"），null=未设 */
    defaultModel: String? = null,
    /** 点星标设置/取消默认模型 */
    onSetDefault: (providerId: String, modelId: String) -> Unit = { _, _ -> },
    /** 2026-08-16（管理入口）：跳转服务器模型管理页（开关/搜索） */
    onManageModels: () -> Unit = {},
) {
    val isAmoled = isAmoledTheme()
    val params = amoledDialogParams(shape = ShapeTokens.largeMedium)

    fun isModelFree(providerId: String, model: ModelCatalog): Boolean {
        if (providerId != "opencode") return false
        return model.costInput == 0.0
    }

    // 提供商排序："opencode" 在前，然后按名称排序
    val sortedProviders = remember(providers) {
        providers
            .filter { it.models.isNotEmpty() }
            .sortedWith(compareBy<ProviderCatalog> { it.id != "opencode" }.thenBy { it.name.lowercase() })
    }

    // 2026-08-12 用户要求：模型选择改为抽屉式（与后台面板一致——ModalBottomSheet，
    // 无拉杆，高度 30%-75% 屏）
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = {},
        containerColor = params.containerColor,
        shape = params.shape,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 2026-08-16（用户决策）：只设上限 75% 屏高（去 30% 下限，内容自然收缩）
                .heightIn(
                    max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.75f
                )
        ) {
            // 2026-08-12 用户要求：抽屉式组件统一标题栏（与快速导航一致）——标题 + 关闭按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.SM.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.a11y_icon_select_model),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // 2026-08-16（管理入口）：模型管理（服务器设置→Models 的快捷入口）
                TextButton(onClick = onManageModels) {
                    Text(stringResource(dev.leonardo.ocbeacon.R.string.chat_manage_models))
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                    )
                }
            }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
                for ((index, provider) in sortedProviders.withIndex()) {
                    val sortedModels = provider.models.values
                        .sortedWith(compareBy<ModelCatalog> { !isModelFree(provider.id, it) }.thenBy { it.name.lowercase() })

                    item(key = "provider_header_${provider.id}") {
                        // 2026-08-12 用户要求：聚合标题样式与正常 item 一致——
                        // 简单图标 + title（移除 uppercase/letterSpacing/次级强调色）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 40.dp)
                                .padding(start = 4.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ProviderIcon(
                                providerId = provider.id,
                                size = 18.dp,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM)
                            )
                            Text(
                                text = provider.name.ifEmpty { provider.id },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    items(
                        sortedModels,
                        key = { "model_${provider.id}_${it.id}" }
                    ) { model ->
                        val isSelected = provider.id == selectedProviderId && model.id == selectedModelId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 2026-08-12 用户要求：单行 item 统一高度（与聚合行一致——40dp 密集规格）
                                .heightIn(min = 40.dp)
                                .clip(ShapeTokens.small)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = AlphaTokens.MUTED)
                                    else Color.Transparent
                                )
                                .clickable {
                                    onSelect(provider.id, model.id)
                                    onDismiss()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 2026-08-12 用户要求：统一单行展示——模型名左 + Free 标签右 + 选中勾
                            Text(
                                text = model.name.ifEmpty { model.id },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (isModelFree(provider.id, model)) {
                                Text(
                                    text = stringResource(R.string.chat_free_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = AlphaTokens.HIGH),
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            // 2026-08-16（方案 A·默认模型）：星标=设/取消默认模型
                            //（职责分离规范：专门按钮承担动作，整行点击仍是选模型）
                            val isDefault = defaultModel == "${provider.id}|${model.id}"
                            IconButton(
                                onClick = { onSetDefault(provider.id, model.id) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = if (isDefault) androidx.compose.material.icons.Icons.Filled.Star else androidx.compose.material.icons.Icons.Outlined.Star,
                                    contentDescription = stringResource(dev.leonardo.ocbeacon.R.string.chat_default_model),
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isDefault) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dev.leonardo.ocbeacon.ui.theme.AlphaTokens.MUTED)
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = stringResource(R.string.a11y_icon_select_model),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
