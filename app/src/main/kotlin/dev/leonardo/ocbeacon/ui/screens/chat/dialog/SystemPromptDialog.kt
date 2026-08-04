package dev.leonardo.ocbeacon.ui.screens.chat.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * 从消息部件列表中提取系统提示词文本。
 * 查找 role 为 "system" 的消息中的 Text 部件，或内容以
 * "System:" 开头的 Text 部件。
 *
 * @param systemParts 来自 system 类型消息的部件列表。
 * @return 拼接后的系统提示词文本，为空时返回 null。
 */
fun extractSystemPrompt(systemParts: List<String>): String? {
    val text = systemParts.filter { it.isNotBlank() }.joinToString("\n\n")
    return text.ifBlank { null }
}

/**
 * 显示当前会话系统提示词的底部表单对话框。
 *
 * @param systemPrompt 要显示的系统提示词文本。
 * @param onDismiss 对话框关闭时的回调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPromptDialog(
    systemPrompt: String?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_system_prompt_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (systemPrompt.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.chat_system_prompt_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                )
            } else {
                Text(
                    text = systemPrompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                Text(stringResource(R.string.close))
            }
        }
    }
}
