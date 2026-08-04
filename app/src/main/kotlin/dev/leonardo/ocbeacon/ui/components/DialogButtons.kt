package dev.leonardo.ocbeacon.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.ui.theme.ButtonTokens

/**
 * 对话框中按钮的角色。
 *
 * - [Primary]：   主要操作（确认、保存、创建）。填充 Button，使用 primary 色。
 * - [Secondary]：取消 / 关闭。OutlinedButton，使用 Material 3 默认颜色。
 * - [Danger]：    破坏性操作（删除、回滚）。填充 Button，使用 error 色。
 */
enum class DialogButtonRole {
    Primary,
    Secondary,
    Danger,
}

/**
 * 统一的对话框按钮行。
 *
 * 布局规则：
 * - 1 个按钮：单 Row，右对齐
 * - 2 个按钮：Row，水平排列，右对齐
 * - 3 个及以上：Column，垂直排列，全宽
 *
 * @param buttons (label, role, onClick) 三元组列表。
 */
@Composable
fun DialogButtons(
    buttons: List<Triple<String, DialogButtonRole, () -> Unit>>,
) {
    if (buttons.size <= 2) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonTokens.RowSpacing.dp, Alignment.End),
        ) {
            buttons.forEach { (text, role, onClick) ->
                DialogActionButton(
                    text = text,
                    role = role,
                    onClick = onClick,
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(ButtonTokens.StackSpacing.dp),
        ) {
            buttons.forEach { (text, role, onClick) ->
                DialogActionButton(
                    text = text,
                    role = role,
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun DialogActionButton(
    text: String,
    role: DialogButtonRole,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val contentPadding = if (compact) ButtonTokens.CompactPadding else ButtonDefaults.ContentPadding
    when (role) {
        DialogButtonRole.Primary -> {
            Button(
                onClick = onClick,
                modifier = modifier,
                colors = ButtonTokens.filledColors(),
                border = ButtonTokens.amoledBorder(),
                contentPadding = contentPadding,
            ) {
                Text(text)
            }
        }
        DialogButtonRole.Secondary -> {
            OutlinedButton(
                onClick = onClick,
                modifier = modifier,
                contentPadding = contentPadding,
            ) {
                Text(text)
            }
        }
        DialogButtonRole.Danger -> {
            Button(
                onClick = onClick,
                modifier = modifier,
                colors = ButtonTokens.dangerColors(),
                border = ButtonTokens.amoledBorder(),
                contentPadding = contentPadding,
            ) {
                Text(text)
            }
        }
    }
}
