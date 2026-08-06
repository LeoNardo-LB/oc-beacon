package dev.leonardo.ocbeacon.ui.screens.server

import dev.leonardo.ocbeacon.logging.AppLogger

import android.content.ClipData
import android.widget.Toast
import dev.leonardo.ocbeacon.BuildConfig
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField

import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.components.DialogButtonRole
import dev.leonardo.ocbeacon.ui.components.DialogButtons
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.components.amoledOutlinedTextFieldColors
import dev.leonardo.ocbeacon.ui.screens.settings.components.SectionHeader
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ButtonTokens
import dev.leonardo.ocbeacon.ui.theme.LocalAmoledMode
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerProvidersScreen(
    onNavigateBack: () -> Unit,
    viewModel: ServerSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isAmoled = LocalAmoledMode.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val clipScope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val popularProviders = listOf("opencode", "anthropic", "github-copilot", "openai", "google", "openrouter", "vercel")
    val connected = uiState.providers.filter { it.connected && (it.providerId != "opencode" || it.hasPaidModels) }
    val connectedIds = connected.map { it.providerId }.toSet()
    val available = uiState.providers
        .filter { it.providerId !in connectedIds }
        .sortedWith(
            compareBy<ProviderToggle> { popularProviders.indexOf(it.providerId).takeIf { idx -> idx >= 0 } ?: Int.MAX_VALUE }
                .thenBy { it.providerName.lowercase() }
        )
    var connectProvider by remember { mutableStateOf<ProviderToggle?>(null) }
    var apiKeyProvider by remember { mutableStateOf<ProviderToggle?>(null) }
    var apiKey by remember { mutableStateOf("") }
    var oauthCode by remember { mutableStateOf("") }
    var oauthBrowserOpened by remember { mutableStateOf(false) }

    // 仅在 OAuth 流程实际启动后才关闭方式选择器。
    LaunchedEffect(uiState.pendingOauth?.providerId, connectProvider?.providerId) {
        val pendingForCurrent = uiState.pendingOauth?.providerId
        if (pendingForCurrent != null && pendingForCurrent == connectProvider?.providerId) {
            connectProvider = null
        }
    }

    LaunchedEffect(uiState.pendingOauth?.providerId) {
        oauthBrowserOpened = false
    }

    // 当 provider 变为已连接时自动关闭 OAuth 对话框（例如浏览器自动回调后）
    LaunchedEffect(connectedIds, uiState.pendingOauth?.providerId) {
        val pendingId = uiState.pendingOauth?.providerId
        if (pendingId != null && pendingId in connectedIds) {
            viewModel.cancelProviderOauth()
        }
    }

    // 如果 headless 方式不可用而回退到浏览器 OAuth，
    // 自动打开浏览器，使整个流程保持一键完成。
    LaunchedEffect(uiState.pendingOauth?.providerId, uiState.pendingOauth?.fallbackFromHeadless, oauthBrowserOpened) {
        val pending = uiState.pendingOauth ?: return@LaunchedEffect
        if (pending.fallbackFromHeadless && !oauthBrowserOpened && pending.authorization.url.isNotBlank()) {
            oauthBrowserOpened = true
            uriHandler.openUri(pending.authorization.url)
        }
    }

    // 用户从浏览器返回时，总是重新加载 providers。
    // 如果服务器上授权已完成，connectedIds effect 会关闭对话框。
    // 如果未完成，对话框保持打开，用户可手动继续。
    DisposableEffect(lifecycleOwner, uiState.pendingOauth?.providerId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val pending = uiState.pendingOauth
                if (BuildConfig.DEBUG) AppLogger.d("ProvidersScreen", "ON_RESUME: browserOpened=$oauthBrowserOpened, pending=${pending?.providerId}, isSaving=${uiState.isSaving}")
                if (oauthBrowserOpened && pending != null && !uiState.isSaving) {
                    if (pending.authorization.method == "code") {
                        viewModel.loadProviders()
                    } else {
                        viewModel.completeProviderOauth(null)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    connectProvider?.let { provider ->
        val methods = uiState.authMethods[provider.providerId].orEmpty().ifEmpty {
            listOf(dev.leonardo.ocbeacon.domain.model.ProviderAuthMethod(type = "api", label = stringResource(R.string.server_settings_auth_method_api)))
        }
        val connectParams = amoledDialogParams(
            normalColor = MaterialTheme.colorScheme.surface,
            shape = ShapeTokens.largeMedium,
        )
        BasicAlertDialog(onDismissRequest = { connectProvider = null }) {
            Surface(
                shape = connectParams.shape,
                color = connectParams.containerColor,
                border = connectParams.border,
                tonalElevation = connectParams.tonalElevation,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.server_settings_connect_provider, provider.providerName),
                        style = MaterialTheme.typography.titleLarge,
                    )

                    methods.forEachIndexed { idx, method ->
                        Button(
                            onClick = {
                                if (method.type == "api") {
                                    connectProvider = null
                                    apiKeyProvider = provider
                                } else {
                                    viewModel.startProviderOauth(provider.providerId, idx)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isSaving,
                            colors = ButtonTokens.filledColors(),
                            border = ButtonTokens.amoledBorder(),
                        ) {
                            Text(method.label)
                        }
                    }

                    uiState.error?.takeIf { it.isNotBlank() }?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                }
            }
        }
    }

    apiKeyProvider?.let { provider ->
        val apiKeyParams = amoledDialogParams(
            normalColor = MaterialTheme.colorScheme.surface,
            shape = ShapeTokens.largeMedium,
        )
        BasicAlertDialog(onDismissRequest = {
            apiKeyProvider = null
            apiKey = ""
        }) {
            Surface(
                shape = apiKeyParams.shape,
                color = apiKeyParams.containerColor,
                border = apiKeyParams.border,
                tonalElevation = apiKeyParams.tonalElevation,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.server_settings_api_key_title, provider.providerName), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.server_settings_api_key_placeholder)) },
                        singleLine = true,
                        colors = if (isAmoled) {
                            amoledOutlinedTextFieldColors()
                        } else androidx.compose.material3.OutlinedTextFieldDefaults.colors()
                    )
                    DialogButtons(
                        buttons = listOf(
                            Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) {
                                apiKeyProvider = null
                                apiKey = ""
                            },
                            Triple(stringResource(R.string.connect), DialogButtonRole.Primary) {
                                viewModel.connectProviderApi(provider.providerId, apiKey)
                                apiKeyProvider = null
                                apiKey = ""
                            },
                        )
                    )
                }
            }
        }
    }

    uiState.pendingOauth?.let { pending ->
        val deviceCode = remember(pending.authorization.instructions) {
            extractOAuthDeviceCode(pending.authorization.instructions)
        }
        val oauthParams = amoledDialogParams(
            normalColor = MaterialTheme.colorScheme.surface,
            shape = ShapeTokens.largeMedium,
        )
        BasicAlertDialog(onDismissRequest = {
            oauthCode = ""
            viewModel.cancelProviderOauth()
        }) {
            Surface(
                shape = oauthParams.shape,
                color = oauthParams.containerColor,
                border = oauthParams.border,
                tonalElevation = oauthParams.tonalElevation,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.server_settings_oauth_title, pending.providerName),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (deviceCode != null) {
                        // 显示本地化提示 + 突出的代码 chip
                        Text(
                            text = stringResource(R.string.server_settings_oauth_device_code_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM),
                        )
                        Surface(
                            shape = ShapeTokens.medium,
                            color = if (isAmoled) {
                                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = AlphaTokens.FAINT)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            border = BorderStroke(
                                1.dp,
                                if (isAmoled) MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MUTED)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipScope.launch {
                                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("device_code", deviceCode)))
                                    }
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.server_settings_oauth_code_copied),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = deviceCode,
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 2.sp,
                                    ),
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Center,
                                )
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.server_settings_oauth_copy_code),
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                                )
                            }
                        }
                    } else if (pending.authorization.method != "code" && pending.authorization.url.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.server_settings_oauth_browser_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else if (pending.authorization.instructions.isNotBlank()) {
                        // 未提取到结构化数据 — 回退显示原始说明
                        Text(
                            text = pending.authorization.instructions,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (pending.fallbackFromHeadless) {
                        Text(
                            text = stringResource(R.string.server_settings_oauth_headless_fallback),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM),
                        )
                    }
                    if (pending.authorization.url.isNotBlank()) {
                        Button(
                            onClick = {
                                oauthBrowserOpened = true
                                uriHandler.openUri(pending.authorization.url)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonTokens.filledColors(),
                            border = ButtonTokens.amoledBorder(),
                        ) {
                            Text(stringResource(R.string.server_settings_oauth_open_browser))
                        }
                    }
                    if (pending.authorization.method == "code") {
                        OutlinedTextField(
                            value = oauthCode,
                            onValueChange = { oauthCode = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.server_settings_oauth_code_placeholder)) },
                            singleLine = true,
                            colors = if (isAmoled) {
                                amoledOutlinedTextFieldColors()
                            } else androidx.compose.material3.OutlinedTextFieldDefaults.colors()
                        )
                    }
                    DialogButtons(
                        buttons = buildList {
                            add(Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) {
                                oauthCode = ""
                                viewModel.cancelProviderOauth()
                            })
                            if (pending.authorization.method == "code") {
                                add(Triple(
                                    stringResource(R.string.server_settings_oauth_complete),
                                    DialogButtonRole.Primary
                                ) {
                                    viewModel.completeProviderOauth(oauthCode)
                                    oauthCode = ""
                                })
                            }
                        }
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_settings_providers)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            uiState.error?.takeIf { it.isNotBlank() }?.let { error ->
                item {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            if (connected.isNotEmpty()) {
                item { SectionHeader(title = stringResource(R.string.server_settings_providers_connected)) }
                items(connected, key = { it.providerId }) { provider ->
                    ListItem(
                        headlineContent = { Text(provider.providerName) },
                        supportingContent = {
                            Column {
                                Text(
                                    text = provider.providerId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM)
                                )
                                provider.source?.let { src ->
                                    Text(
                                        text = when (src) {
                                            "env" -> stringResource(R.string.server_settings_provider_source_env)
                                            "api" -> stringResource(R.string.server_settings_provider_source_api)
                                            "config" -> stringResource(R.string.server_settings_provider_source_config)
                                            "custom" -> stringResource(R.string.server_settings_provider_source_custom)
                                            else -> stringResource(R.string.server_settings_provider_source_other)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            if (provider.source != "env") {
                                Button(
                                    onClick = { viewModel.disconnectProvider(provider.providerId) },
                                    enabled = !uiState.isSaving,
                                    colors = ButtonTokens.filledColors(),
                                    border = ButtonTokens.amoledBorder(),
                                ) {
                                    Text(stringResource(R.string.disconnect))
                                }
                            } else {
                                Text(
                                    text = stringResource(R.string.server_settings_provider_env_connected),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED)
                                )
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }

            if (available.isNotEmpty()) {
                item { SectionHeader(title = stringResource(R.string.server_settings_providers_available)) }
                items(available, key = { it.providerId }) { provider ->
                    ListItem(
                        headlineContent = { Text(provider.providerName) },
                        supportingContent = {
                            Text(
                                text = provider.providerId,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM)
                            )
                        },
                        trailingContent = {
                            Button(
                                onClick = { viewModel.clearError(); connectProvider = provider },
                                enabled = !uiState.isSaving,
                                colors = ButtonTokens.filledColors(),
                                border = ButtonTokens.amoledBorder(),
                            ) {
                                Text(stringResource(R.string.connect))
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun extractOAuthDeviceCode(instructions: String): String? {
    val codePattern = Regex("\\b[A-Z0-9]{3,}(?:-[A-Z0-9]{3,})+\\b")
    return codePattern.find(instructions)?.value
}
