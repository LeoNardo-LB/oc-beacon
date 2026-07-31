package dev.leonardo.ocbeacon.ui.screens.settings.components

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
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.components.AppPickerList


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguagePickerDialog(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val systemDefault = stringResource(R.string.settings_language_system)
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 520.dp),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.dialog_select_language),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                AppPickerList(
                    options = listOf(
                        "" to systemDefault,
                        "en" to "English",
                        "ar" to "العربية",
                        "de" to "Deutsch",
                        "es" to "Español",
                        "fr" to "Français",
                        "id" to "Bahasa Indonesia",
                        "it" to "Italiano",
                        "ja" to "日本語",
                        "ko" to "한국어",
                        "pl" to "Polski",
                        "pt-BR" to "Português (Brasil)",
                        "ru" to "Русский",
                        "tr" to "Türkçe",
                        "uk" to "Українська",
                        "zh-CN" to "简体中文"
                    ),
                    selectedKey = currentLanguage,
                    onSelect = onLanguageSelected,
                )
            }
        }
    }
}
