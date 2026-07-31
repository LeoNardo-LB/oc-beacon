package dev.leonardo.ocbeacon.ui.screens.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import kotlinx.coroutines.launch

/**
 * Bottom sheet for entering or editing a modification note.
 *
 * @param selectedText The code the user selected (preview, read-only).
 * @param initialNote Pre-filled note (editing mode). Empty for new annotation.
 * @param onConfirm Called with the entered note when user taps confirm.
 * @param onDismiss Called when sheet is dismissed.
 * @param onDelete When non-null, shows a delete button (edit mode).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationInputSheet(
    selectedText: String,
    initialNote: String = "",
    onConfirm: (note: String) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf(initialNote) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingTokens.LG.dp)
                .padding(bottom = SpacingTokens.XXL.dp),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.MD.dp)
        ) {
            Text(
                text = stringResource(R.string.annotation_input_title),
                style = MaterialTheme.typography.titleMedium
            )

            // Selected text preview
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = selectedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(SpacingTokens.MD.dp)
                        .heightIn(max = 150.dp)
                        .verticalScroll(rememberScrollState())
                )
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.annotation_input_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag("annotation_input_note"),
                minLines = 2,
                maxLines = 5
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Delete button (edit mode only)
                if (onDelete != null) {
                    TextButton(onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDelete() }
                    }) { Text(stringResource(R.string.annotation_detail_delete)) }
                }

                Row {
                    TextButton(onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                    }) { Text(stringResource(R.string.cancel)) }

                    TextButton(
                        onClick = {
                            if (note.isNotBlank()) {
                                scope.launch { sheetState.hide() }.invokeOnCompletion { onConfirm(note.trim()) }
                            }
                        },
                        enabled = note.isNotBlank(),
                        modifier = Modifier.testTag("annotation_input_confirm")
                    ) { Text(stringResource(R.string.annotation_input_confirm)) }
                }
            }
        }
    }
}
