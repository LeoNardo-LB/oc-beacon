package dev.leonardo.octether.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
internal fun ThemePickerDialog(
    currentTheme: String,
    onThemeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 480.dp),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.dialog_select_theme),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                AppPickerList(
                    options = listOf(
                        "system" to stringResource(R.string.settings_theme_system),
                        "light" to stringResource(R.string.settings_theme_light),
                        "dark" to stringResource(R.string.settings_theme_dark)
                    ),
                    selectedKey = currentTheme,
                    onSelect = onThemeSelected,
                )
            }
        }
    }
}
