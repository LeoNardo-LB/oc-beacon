package dev.leonardo.ocbeacon.ui.screens.viewer

import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R

/**
 * Adds "Annotate" item to the system text context menu via official
 * [Modifier.appendTextContextMenuComponents] API.
 *
 * When clicked: captures selected text via clipboard, strips line-number
 * gutter prefixes, and calls [onAnnotate].
 *
 * Usage: wrap content in `SelectionContainer`, apply this modifier to `Text`.
 *
 * @param onAnnotate callback with the captured selected text
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
            // Clipboard capture: Android system copies selection to clipboard
            // when context menu is shown. Read it here.
            val selectedText = clipboard.getText()?.text.orEmpty()
            val cleaned = stripGutterNumbers(selectedText)
            if (cleaned.isNotBlank()) {
                onAnnotate(cleaned)
            }
            close()
        }
    }
}

/** Unique key for the annotation context menu item. */
private data object AnnotationMenuKey

/**
 * Strip line-number gutter prefixes from clipboard-captured text.
 * Gutter Text composables inside SelectionContainer may add line numbers
 * to the selected text. This regex removes "digits + optional whitespace" at
 * line starts. The trailing `\s?` is optional because the new Column-based
 * layout (gutter Column | code Column) may concatenate "1code" with no space
 * between the line number and the code text.
 */
internal fun stripGutterNumbers(text: String): String {
    return text.replace(Regex("(?m)^\\s*\\d+\\s?"), "")
}
