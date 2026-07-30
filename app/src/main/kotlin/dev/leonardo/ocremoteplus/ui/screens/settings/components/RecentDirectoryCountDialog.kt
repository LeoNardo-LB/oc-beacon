package dev.leonardo.ocremoteplus.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.ocremoteplus.R
import dev.leonardo.ocremoteplus.ui.components.amoledDialogParams
import dev.leonardo.ocremoteplus.ui.components.DialogButtons
import dev.leonardo.ocremoteplus.ui.components.DialogButtonRole
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecentDirectoryCountDialog(
    currentCount: Int,
    onCountSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(currentCount) { mutableFloatStateOf(currentCount.coerceIn(5, 50).toFloat()) }
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.settings_recent_directory_count),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_recent_directory_count_value, selected.roundToInt()),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Slider(
                    value = selected,
                    onValueChange = { selected = it },
                    valueRange = 5f..50f,
                    steps = 44,
                )
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary, onDismiss),
                        Triple(stringResource(R.string.ok), DialogButtonRole.Primary) {
                            onCountSelected(selected.roundToInt())
                        },
                    )
                )
            }
        }
    }
}
