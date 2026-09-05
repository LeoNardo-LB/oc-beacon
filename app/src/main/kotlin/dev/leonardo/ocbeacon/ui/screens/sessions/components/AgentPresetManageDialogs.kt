package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.AgentPreset
import dev.leonardo.ocbeacon.domain.model.DshAgentPresetDocument
import dev.leonardo.ocbeacon.ui.components.DialogButtonRole
import dev.leonardo.ocbeacon.ui.components.DialogButtons
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.components.amoledOutlinedTextFieldColors
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens

/**
 * #324② preset 管理对话框组（AgentPresetDefaultRow 展开行的动作宿主）。
 *
 * - 组成查看：agentPresets/read 只读 content（服务端 locale 解析原文，客户端
 *   不编辑——移动端编辑走服务端文件，对齐 web read-only viewer 裁决）；
 * - 复制：agentPresets/copy(from,id,name?) → user 预设；
 * - 删除确认：仅 user 档入口渲染（system 档服务端恒拒）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentPresetContentDialog(
    document: DshAgentPresetDocument,
    onDismiss: () -> Unit,
) {
    val dialogParams = amoledDialogParams(
        normalColor = MaterialTheme.colorScheme.surface,
        shape = ShapeTokens.largeMedium,
    )
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = dialogParams.shape,
            color = dialogParams.containerColor,
            border = dialogParams.border,
            tonalElevation = dialogParams.tonalElevation,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = document.name ?: document.agentPreset,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = document.content,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.close), DialogButtonRole.Primary) { onDismiss() },
                    ),
                )
            }
        }
    }
}

/** 复制预设对话框（新 id 必填 + 名称可选）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentPresetCopyDialog(
    preset: AgentPreset,
    onConfirm: (newId: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newId by remember { mutableStateOf(preset.id + "-copy") }
    var name by remember { mutableStateOf("") }
    val dialogParams = amoledDialogParams(
        normalColor = MaterialTheme.colorScheme.surface,
        shape = ShapeTokens.largeMedium,
    )
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = dialogParams.shape,
            color = dialogParams.containerColor,
            border = dialogParams.border,
            tonalElevation = dialogParams.tonalElevation,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.dsh_preset_copy_title, preset.name),
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedTextField(
                    value = newId,
                    onValueChange = { newId = it },
                    label = { Text(stringResource(R.string.dsh_preset_copy_id)) },
                    singleLine = true,
                    colors = amoledOutlinedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.dsh_preset_copy_name)) },
                    singleLine = true,
                    colors = amoledOutlinedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    DialogButtons(
                        buttons = listOf(
                            Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) { onDismiss() },
                            Triple(
                                stringResource(R.string.dsh_preset_copy),
                                DialogButtonRole.Primary,
                            ) {
                                if (newId.isNotBlank()) onConfirm(newId.trim(), name.trim())
                            },
                        ),
                    )
                }
            }
        }
    }
}

/** 删除确认（user 预设）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentPresetDeleteConfirmDialog(
    preset: AgentPreset,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dialogParams = amoledDialogParams(
        normalColor = MaterialTheme.colorScheme.surface,
        shape = ShapeTokens.largeMedium,
    )
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = dialogParams.shape,
            color = dialogParams.containerColor,
            border = dialogParams.border,
            tonalElevation = dialogParams.tonalElevation,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.dsh_preset_delete_confirm_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.dsh_preset_delete_confirm_text, preset.name),
                    style = MaterialTheme.typography.bodyMedium,
                )
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) { onDismiss() },
                        Triple(stringResource(R.string.delete), DialogButtonRole.Danger) {
                            onConfirm()
                            onDismiss()
                        },
                    ),
                )
            }
        }
    }
}
