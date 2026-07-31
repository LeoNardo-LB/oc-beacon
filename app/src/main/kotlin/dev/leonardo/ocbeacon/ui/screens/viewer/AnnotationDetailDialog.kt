package dev.leonardo.ocbeacon.ui.screens.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Annotation
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * Dialog showing annotation details when user taps an existing highlight.
 * Provides Edit / Delete actions.
 */
@Composable
fun AnnotationDetailDialog(
    annotation: Annotation,
    onEdit: (newNote: String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var editNote by remember { mutableStateOf(annotation.note) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.annotation_detail_title, annotation.index + 1),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)) {
                Text(
                    text = stringResource(R.string.annotation_detail_selected),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = annotation.selectedText,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .heightIn(max = 120.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(SpacingTokens.SM.dp)
                    )
                }

                Text(
                    text = "${annotation.startLine}:${annotation.startCol} - ${annotation.endLine}:${annotation.endCol}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isEditing) {
                    OutlinedTextField(
                        value = editNote,
                        onValueChange = { editNote = it },
                        label = { Text(stringResource(R.string.annotation_input_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2, maxLines = 5
                    )
                } else {
                    Text(
                        text = annotation.note,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = SpacingTokens.XS.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (isEditing) {
                TextButton(onClick = { onEdit(editNote.trim()); onDismiss() }) {
                    Text(stringResource(R.string.annotation_detail_save))
                }
            } else {
                TextButton(onClick = { isEditing = true }) {
                    Text(stringResource(R.string.annotation_detail_edit))
                }
            }
        },
        dismissButton = {
            if (isEditing) {
                TextButton(onClick = { isEditing = false; editNote = annotation.note }) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                TextButton(
                    onClick = { onDelete(); onDismiss() },
                    modifier = Modifier.testTag("annotation_detail_delete")
                ) {
                    Text(
                        stringResource(R.string.annotation_detail_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    )
}
