package dev.leonardo.octether.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.octether.R
import dev.leonardo.octether.ui.components.amoledDialogParams
import dev.leonardo.octether.ui.components.AppPickerList


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageCompressionMaxSideDialog(
    currentMaxSide: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(0, 720, 960, 1080, 1440, 1920, 2560)
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
                    text = stringResource(R.string.settings_compress_images_max_side),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                AppPickerList(
                    options = options.map { it to getImageMaxSideDisplayName(it) },
                    selectedKey = currentMaxSide,
                    onSelect = onSelected,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageCompressionQualityDialog(
    currentQuality: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(40, 50, 60, 70, 80)
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
                    text = stringResource(R.string.settings_compress_images_quality),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                AppPickerList(
                    options = options.map {
                        it to stringResource(R.string.settings_compress_images_quality_value, it)
                    },
                    selectedKey = currentQuality,
                    onSelect = onSelected,
                )
            }
        }
    }
}
