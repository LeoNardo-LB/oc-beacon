package dev.leonardo.ocbeacon.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R

/**
 * #317：DSH 0.1.2 TokenNeeded 细条幅（会话列表 TopAppBar 下沿，优先于
 * [ServerLinkBanner]——token 需求比一般断连更具体，给出路而非干等）。
 * 形态对齐 ServerLinkBanner：errorContainer 底 + 图标 + 文案；右侧附输入入口。
 */
@Composable
fun DshTokenNeededBanner(
    onEnterToken: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        // C2d 仪器验证发现的布局缺陷：Scaffold topBar Column 不自动避让状态栏，
        // 横幅顶部 ~105px 沉入状态栏（视觉遮挡 + 系统拦截触摸 → 「输入令牌」
        // 按钮不可点）。statusBarsPadding 把内容下推到状态栏下沿。
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Key,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(R.string.dsh_token_banner_text),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp),
            )
            TextButton(onClick = onEnterToken) {
                Text(
                    text = stringResource(R.string.dsh_token_banner_action),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * #317：token 输入对话框——粘贴 URL / 启动行 / 裸 token 三形态
 * （[dev.leonardo.ocbeacon.data.api.dsh.extractDshToken] 解析）；
 * 交换成功由连接循环 awaitCookie 自动续行（对话框只报结果，不手动重连）。
 */
@Composable
fun DshTokenDialog(
    exchanging: Boolean,
    rejected: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dsh_token_dialog_title)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.dsh_token_dialog_hint)) },
                supportingText = {
                    Text(
                        if (rejected) stringResource(R.string.dsh_token_dialog_rejected)
                        else stringResource(R.string.dsh_token_dialog_message),
                    )
                },
                isError = rejected,
                enabled = !exchanging,
                singleLine = false,
                maxLines = 3,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(input) },
                enabled = !exchanging && input.isNotBlank(),
            ) {
                Text(stringResource(R.string.dsh_token_dialog_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
