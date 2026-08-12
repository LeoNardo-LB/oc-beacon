package dev.leonardo.ocbeacon.ui.screens.chat.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.ModelCatalog
import dev.leonardo.ocbeacon.domain.model.ProviderCatalog
import dev.leonardo.ocbeacon.ui.components.ProviderIcon
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerDialog(
    providers: List<ProviderCatalog>,
    selectedProviderId: String?,
    selectedModelId: String?,
    onSelect: (providerId: String, modelId: String) -> Unit,
    onDismiss: () -> Unit
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

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = params.shape,
            color = params.containerColor,
            border = params.border,
            tonalElevation = params.tonalElevation,
            modifier = Modifier.fillMaxWidth()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    // 2026-08-12 用户要求：与后台面板高度一致（30%-60% 屏高）
                    .heightIn(
                        min = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.3f,
                        max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.6f
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                for ((index, provider) in sortedProviders.withIndex()) {
                    val topPad = if (index == 0) 0.dp else 12.dp

                    val sortedModels = provider.models.values
                        .sortedWith(compareBy<ModelCatalog> { !isModelFree(provider.id, it) }.thenBy { it.name.lowercase() })

                    item(key = "provider_header_${provider.id}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = topPad, bottom = 2.dp, start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ProviderIcon(
                                providerId = provider.id,
                                size = 14.dp,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM)
                            )
                            Text(
                                text = (provider.name.ifEmpty { provider.id }).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                                letterSpacing = 1.sp
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = model.name.ifEmpty { model.id },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isModelFree(provider.id, model)) {
                                    Text(
                                        text = stringResource(R.string.chat_free_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = AlphaTokens.HIGH)
                                    )
                                }
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
