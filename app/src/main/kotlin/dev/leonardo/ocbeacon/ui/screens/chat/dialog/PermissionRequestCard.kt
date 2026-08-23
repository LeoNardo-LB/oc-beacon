package dev.leonardo.ocbeacon.ui.screens.chat.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.screens.chat.util.performHaptic
import dev.leonardo.ocbeacon.ui.theme.CodeTypography
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import dev.leonardo.ocbeacon.ui.components.AmoledSurface
import dev.leonardo.ocbeacon.ui.components.DialogButtonRole
import dev.leonardo.ocbeacon.ui.components.DialogButtons
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PermissionCard(
    permission: SseEvent.PermissionAsked,
    onOnce: () -> Unit,
    onAlways: () -> Unit,
    onReject: () -> Unit,
    positionLabel: String? = null
) {
    val isAmoled = isAmoledTheme()
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    var submitted by remember(permission.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 权限卡视觉复审（2026-08-19）：从独立 Card(errorContainer + medium 12dp +
    // 自定义 error 边框) 迁入统一交互卡骨架——与提问卡/工具卡（ToolCardScaffold
    // 语言）同构：AmoledSurface + smallMedium 6dp + tonal 1dp，AMOLED 纯黑 +
    // 标准边框（参照 ToolCardScaffold 状态色模式：语义色仅普通主题生效）。
    // error 红语义经 normalColor=errorContainer 透传（与 Question 的中性 surface
    // 区分安全敏感性）；文字/图标语义色两主题保留。
    val contentColor = if (isAmoled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onErrorContainer
    val accentTint = MaterialTheme.colorScheme.error

    AmoledSurface(
        isAmoledDark = isAmoled,
        normalColor = MaterialTheme.colorScheme.errorContainer,
        shape = ShapeTokens.smallMedium,
        normalTonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(SpacingTokens.MD.dp),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)
        ) {
            // 头部行：安全图标 + "权限请求"标题
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = stringResource(R.string.permission_title),
                    modifier = Modifier.size(20.dp),
                    tint = accentTint
                )
                Text(
                    text = stringResource(R.string.permission_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor
                )
                if (positionLabel != null) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = positionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = AlphaTokens.MUTED)
                    )
                }
                if (submitted) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(16.dp)
                            .padding(start = 4.dp),
                        strokeWidth = 2.dp,
                        color = contentColor
                    )
                }
            }
            // 子智能体来源标签（当权限请求来自子智能体会话时显示）
            if (permission.sourceSessionTitle != null) {
                Text(
                    text = permission.sourceSessionTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = AlphaTokens.MEDIUM)
                )
            }
            // 权限描述
            Text(
                text = permission.permission,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor
            )
            // 文件模式（如果有）
            if (permission.patterns.isNotEmpty()) {
                Text(
                    text = permission.patterns.joinToString(", "),
                    style = CodeTypography.copy(
                        fontSize = 11.sp,
                        color = contentColor.copy(alpha = AlphaTokens.MEDIUM)
                    ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 操作按钮
            DialogButtons(
                buttons = listOf(
                    Triple(stringResource(R.string.permission_deny), DialogButtonRole.Danger) {
                        if (!submitted) {
                            performHaptic(hapticView, hapticOn); submitted = true; onReject()
                            scope.launch { delay(5_000); submitted = false }
                        }
                    },
                    Triple(stringResource(R.string.permission_allow_once), DialogButtonRole.Primary) {
                        if (!submitted) {
                            performHaptic(hapticView, hapticOn); submitted = true; onOnce()
                            scope.launch { delay(5_000); submitted = false }
                        }
                    },
                    Triple(stringResource(R.string.permission_allow_always), DialogButtonRole.Secondary) {
                        if (!submitted) {
                            performHaptic(hapticView, hapticOn); onAlways()
                            // 此处不设置 submitted=true —— onAlways 会打开确认对话框。
                            // submitted 在用户实际确认时设置（onConfirm in AlwaysConfirmDialog）。
                        }
                    },
                )
            )
        }
    }
}
