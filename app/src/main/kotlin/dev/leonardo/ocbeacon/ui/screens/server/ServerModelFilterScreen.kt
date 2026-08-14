package dev.leonardo.ocbeacon.ui.screens.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.components.amoledOutlinedTextFieldColors
import dev.leonardo.ocbeacon.ui.screens.settings.components.SectionHeader
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ButtonTokens
import dev.leonardo.ocbeacon.ui.theme.LocalAmoledMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerModelFilterScreen(
    onNavigateBack: () -> Unit,
    viewModel: ServerSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isAmoled = LocalAmoledMode.current
    var search by remember { mutableStateOf("") }

    // L-15：过滤结果 remember——原实现每次重组全量重算（无 remember/derivedStateOf）
    val filteredGroups = remember(search, uiState.groups) {
        val normalized = search.trim().lowercase()
        uiState.groups.mapNotNull { group ->
            val models = group.models.filter {
                normalized.isEmpty() ||
                    it.modelName.lowercase().contains(normalized) ||
                    it.modelId.lowercase().contains(normalized)
            }
            if (models.isEmpty()) return@mapNotNull null
            group.copy(models = models)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_settings_models)) },
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
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.a11y_icon_search))
                },
                placeholder = { Text(stringResource(R.string.server_settings_search_placeholder)) },
                singleLine = true,
                colors = if (isAmoled) {
                    amoledOutlinedTextFieldColors()
                } else {
                    androidx.compose.material3.OutlinedTextFieldDefaults.colors()
                }
            )
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))

            when {
                uiState.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.loading))
                    }
                }

                uiState.error != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Warning, contentDescription = stringResource(R.string.a11y_icon_warning), tint = MaterialTheme.colorScheme.error)
                            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                            Text(uiState.error ?: stringResource(R.string.server_settings_load_error))
                            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { viewModel.loadProviders() },
                                colors = ButtonTokens.filledColors(),
                                border = ButtonTokens.amoledBorder(),
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }

                filteredGroups.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.server_settings_empty))
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        items(filteredGroups, key = { it.providerId }) { group ->
                            SectionHeader(title = group.providerName)
                            group.models.forEach { model ->
                                ListItem(
                                    headlineContent = { Text(model.modelName) },
                                    supportingContent = {
                                        Text(
                                            text = model.modelId,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM)
                                        )
                                    },
                                    trailingContent = {
                                        Switch(
                                            checked = model.visible,
                                            onCheckedChange = { checked ->
                                                viewModel.setModelVisible(group.providerId, model.modelId, checked)
                                            },
                                            colors = SwitchDefaults.colors()
                                        )
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}
