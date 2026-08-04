package dev.leonardo.ocbeacon.ui.screens.chat.components

import android.content.ClipData
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 将 [text] 复制到剪贴板的小型复制按钮。
 *
 * 使用 Material 3 [AnimatedContent] 实现原生图标过渡（复制 → 对勾），
 * 并调用 [onCopied] 回调以便在屏幕层级通过 Snackbar 反馈。
 */
@Composable
fun CopyButton(
    text: String,
    modifier: Modifier = Modifier,
    contentDescription: String = "Copy",
    onCopied: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboard.current
    val clipScope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    IconButton(
        onClick = {
            clipScope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("copy", text)))
                copied = true
                onCopied?.invoke()
                delay(1500)
                copied = false
            }
        },
        modifier = modifier
    ) {
        AnimatedContent(
            targetState = copied,
            transitionSpec = {
                (fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.5f))
                    .togetherWith(fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.5f))
            },
            label = "copyIcon"
        ) { isCopied ->
            Icon(
                imageVector = if (isCopied) Icons.Filled.Check else Icons.Default.ContentCopy,
                contentDescription = contentDescription,
                tint = if (isCopied) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
