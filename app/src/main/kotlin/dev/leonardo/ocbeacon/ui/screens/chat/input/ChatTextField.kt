package dev.leonardo.ocbeacon.ui.screens.chat.input

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * The main text input field for the chat bar. Handles shell-mode monospace styling,
 * file-mention visual transformation, and placeholder rendering.
 *
 * Uses [RowScope] receiver for the `weight` modifier.
 */
@Composable
internal fun RowScope.ChatTextField(
    textFieldValue: TextFieldValue,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    isShellMode: Boolean,
    isAmoled: Boolean,
    confirmedFilePaths: Set<String>
) {
    val text = textFieldValue.text

    val mentionHighlightColor = MaterialTheme.colorScheme.primary
    val mentionBgColor = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.SELECTED)
    val visualTransformation = remember(confirmedFilePaths, mentionHighlightColor, mentionBgColor) {
        if (isShellMode) {
            VisualTransformation.None
        } else {
            FileMentionVisualTransformation(confirmedFilePaths, mentionHighlightColor, mentionBgColor)
        }
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .clip(ShapeTokens.largeMedium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.MUTED))
            .then(
                when {
                    isShellMode -> Modifier.border(
                        width = if (isAmoled) 1.5.dp else 1.dp,
                        color = if (isAmoled) {
                            MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.AMOLED)
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM)
                        },
                        shape = ShapeTokens.largeMedium
                    )
                    isAmoled -> Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MEDIUM),
                        shape = ShapeTokens.largeMedium
                    )
                    else -> Modifier
                }
            )
            .padding(horizontal = SpacingTokens.LG.dp, vertical = 10.dp)
    ) {
        // Fixed min-height box: ensures consistent height regardless of
        // BasicTextField's internal measurement difference between empty (cursor)
        // and non-empty (text line) states. Always renders placeholder to keep
        // measurement baseline stable.
        Box(modifier = Modifier.defaultMinSize(minHeight = with(LocalDensity.current) {
            MaterialTheme.typography.bodyLarge.lineHeight.toDp()
        })) {
            BasicTextField(
                value = textFieldValue,
                onValueChange = onTextFieldValueChange,
                modifier = Modifier
                    .testTag("chat-input")
                    .fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = if (isShellMode) FontFamily.Monospace else FontFamily.Default
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                maxLines = 5,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = visualTransformation,
                decorationBox = { innerTextField ->
                    // Always render placeholder to maintain stable measurement.
                    // Alpha controls visibility without affecting layout.
                    Box {
                        Text(
                            text = placeholder,
                            modifier = Modifier.alpha(if (text.isEmpty()) 1f else 0f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                        )
                        innerTextField()
                    }
                }
            )
        }
    }
}
