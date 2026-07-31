package dev.leonardo.octether.ui.screens.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.octether.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(
    onNavigateBack: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenModels: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.server_settings_providers)) },
                supportingContent = { Text(stringResource(R.string.server_settings_providers_desc)) },
                leadingContent = { Icon(Icons.Default.Hub, contentDescription = stringResource(R.string.a11y_icon_providers)) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.a11y_icon_navigate_forward)) },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clickable(onClick = onOpenProviders)
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.server_settings_models)) },
                supportingContent = { Text(stringResource(R.string.server_settings_models_desc)) },
                leadingContent = { Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.a11y_icon_models)) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.a11y_icon_navigate_forward)) },
                modifier = Modifier.clickable(onClick = onOpenModels)
            )
        }
    }
}
