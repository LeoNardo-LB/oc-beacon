package dev.leonardo.ocbeacon.ui.screens.viewer

import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R

/**
 * 通过官方 [Modifier.appendTextContextMenuComponents] API 把"批注"项
 * 添加到系统文本上下文菜单。
 *
 * 点击时：通过剪贴板捕获选中文本，剥离行号 gutter 前缀，
 * 然后调用 [onAnnotate]。
 *
 * 用法：把内容包在 `SelectionContainer` 中，并把此 modifier 应用到 `Text`。
 *
 * @param onAnnotate 带捕获的选中文本的回调
 */
fun Modifier.annotationContextMenu(
    onAnnotate: (selectedText: String) -> Unit,
): Modifier = composed {
    val clipboard = LocalClipboardManager.current
    val menuLabel = stringResource(R.string.annotation_context_annotate)

    this.appendTextContextMenuComponents {
        item(
            key = AnnotationMenuKey,
            label = menuLabel,
        ) {
            // 剪贴板捕获：上下文菜单显示时，Android 系统会把选区复制到剪贴板。
            // 在此处读取。
            val selectedText = clipboard.getText()?.text.orEmpty()
            val cleaned = stripGutterNumbers(selectedText)
            if (cleaned.isNotBlank()) {
                onAnnotate(cleaned)
            }
            close()
        }
    }
}

/** 批注上下文菜单项的唯一 key。 */
private data object AnnotationMenuKey

/**
 * 从剪贴板捕获的文本中剥离行号 gutter 前缀。
 * SelectionContainer 内的 gutter Text composable 可能会把行号加入
 * 选中文本。此正则去除行首的"数字 + 可选空白"。末尾的 `\s?` 是
 * 可选的，因为新的基于 Column 的布局（gutter Column | code Column）
 * 可能把 "1code" 拼在一起，行号与代码文本之间没有空格。
 */
internal fun stripGutterNumbers(text: String): String {
    return text.replace(Regex("(?m)^\\s*\\d+\\s?"), "")
}
