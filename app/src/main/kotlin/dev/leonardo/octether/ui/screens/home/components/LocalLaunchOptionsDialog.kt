package dev.leonardo.octether.ui.screens.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.octether.R
import dev.leonardo.octether.data.repository.LocalServerManager
import dev.leonardo.octether.ui.components.AmoledDefaultBorder
import dev.leonardo.octether.ui.components.AppPickerList
import dev.leonardo.octether.ui.components.DialogButtonRole
import dev.leonardo.octether.ui.components.DialogButtons
import dev.leonardo.octether.ui.components.amoledDialogParams
import dev.leonardo.octether.ui.components.amoledOutlinedTextFieldColors
import dev.leonardo.octether.ui.theme.AlphaTokens
import dev.leonardo.octether.ui.theme.LocalAmoledMode
import dev.leonardo.octether.ui.theme.ShapeTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalLaunchOptionsDialog(
    enabled: Boolean,
    proxyUrl: String,
    noProxyList: String,
    allowLanAccess: Boolean,
    serverUsername: String,
    serverPassword: String,
    runInBackground: Boolean,
    autoStart: Boolean,
    startupTimeoutSec: Int,
    onDismiss: () -> Unit,
    onProxyEnabledChange: (Boolean) -> Unit,
    onProxyUrlChange: (String) -> Unit,
    onNoProxyListChange: (String) -> Unit,
    onAllowLanAccessChange: (Boolean) -> Unit,
    onServerUsernameChange: (String) -> Unit,
    onServerPasswordChange: (String) -> Unit,
    onRunInBackgroundChange: (Boolean) -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
    onStartupTimeoutSecChange: (Int) -> Unit,
) {
    val isAmoled = LocalAmoledMode.current
    val timeoutOptions = listOf(15, 30, 45, 60, 90, 120)
    var localServerUsername by remember(serverUsername) { mutableStateOf(serverUsername) }
    var localServerPassword by remember(serverPassword) { mutableStateOf(serverPassword) }
    var localProxyUrl by remember(proxyUrl) { mutableStateOf(proxyUrl) }
    var localNoProxyList by remember(noProxyList) { mutableStateOf(noProxyList) }
    var maskPassword by remember { mutableStateOf(true) }
    var maskProxy by remember { mutableStateOf(true) }
    var showTimeoutDialog by remember { mutableStateOf(false) }
    val fieldColors = if (isAmoled) {
        amoledOutlinedTextFieldColors()
    } else {
        OutlinedTextFieldDefaults.colors()
    }

    val switchColors = SwitchDefaults.colors()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val containerColor = MaterialTheme.colorScheme.surface
        Surface(modifier = Modifier.fillMaxSize(), color = containerColor) {
            Scaffold(
                containerColor = containerColor,
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = stringResource(R.string.home_local_launch_options_title),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                            }
                        },
                    )
                },
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(vertical = 4.dp)
                        .navigationBarsPadding()
                        .imePadding()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.home_local_network_section),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.home_local_allow_lan_access)) },
                        supportingContent = { Text(stringResource(R.string.home_local_allow_lan_access_desc)) },
                        trailingContent = {
                            Switch(checked = allowLanAccess, onCheckedChange = onAllowLanAccessChange, colors = switchColors)
                        },
                        modifier = Modifier.clickable { onAllowLanAccessChange(!allowLanAccess) },
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = stringResource(R.string.home_local_security_section),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                    OutlinedTextField(
                        value = localServerUsername,
                        onValueChange = {
                            localServerUsername = it
                            onServerUsernameChange(it.trim())
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        singleLine = true,
                        label = { Text(stringResource(R.string.home_local_server_username_label)) },
                        placeholder = { Text(stringResource(R.string.home_local_server_username_placeholder)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        colors = fieldColors,
                    )
                    OutlinedTextField(
                        value = localServerPassword,
                        onValueChange = {
                            localServerPassword = it
                            onServerPasswordChange(it.trim())
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        singleLine = true,
                        label = { Text(stringResource(R.string.home_local_server_password_label)) },
                        placeholder = { Text(stringResource(R.string.home_local_server_password_placeholder)) },
                        visualTransformation = if (maskPassword) FullStringMaskTransformation else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { maskPassword = !maskPassword }) {
                                Icon(
                                    imageVector = if (maskPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = stringResource(R.string.a11y_icon_toggle_password),
                                )
                            }
                        },
                        colors = fieldColors,
                    )
                    if (allowLanAccess && localServerPassword.isBlank()) {
                        Text(
                            text = stringResource(R.string.home_local_lan_password_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = stringResource(R.string.home_local_proxy_section),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.home_local_proxy_enable)) },
                        supportingContent = { Text(stringResource(R.string.home_local_proxy_url_label)) },
                        trailingContent = {
                            Switch(checked = enabled, onCheckedChange = onProxyEnabledChange, colors = switchColors)
                        },
                        modifier = Modifier.clickable { onProxyEnabledChange(!enabled) },
                    )
                    if (enabled) {
                        OutlinedTextField(
                            value = localProxyUrl,
                            onValueChange = {
                                localProxyUrl = it
                                onProxyUrlChange(it.trim())
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            singleLine = true,
                            label = { Text(stringResource(R.string.home_local_proxy_url_label)) },
                            placeholder = { Text("http://127.0.0.1:8080") },
                            visualTransformation = if (maskProxy) FullStringMaskTransformation else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            trailingIcon = {
                                IconButton(onClick = { maskProxy = !maskProxy }) {
                                    Icon(
                                        imageVector = if (maskProxy) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = stringResource(R.string.a11y_icon_toggle_password),
                                    )
                                }
                            },
                            colors = fieldColors,
                        )
                        OutlinedTextField(
                            value = localNoProxyList,
                            onValueChange = {
                                localNoProxyList = it
                                onNoProxyListChange(it.trim())
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            minLines = 2,
                            maxLines = 4,
                            label = { Text(stringResource(R.string.home_local_proxy_no_proxy_label)) },
                            placeholder = { Text(LocalServerManager.DEFAULT_NO_PROXY_LIST) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            colors = fieldColors,
                        )
                    }
                    Text(
                        text = stringResource(R.string.home_local_proxy_no_proxy_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = stringResource(R.string.home_local_autostart_section),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.home_local_run_background_label)) },
                        supportingContent = { Text(stringResource(R.string.home_local_run_background_desc)) },
                        trailingContent = {
                            Switch(checked = runInBackground, onCheckedChange = onRunInBackgroundChange, colors = switchColors)
                        },
                        modifier = Modifier.clickable { onRunInBackgroundChange(!runInBackground) },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.home_local_auto_start_label)) },
                        supportingContent = {
                            Text(
                                if (runInBackground) {
                                    stringResource(R.string.home_local_auto_start_desc)
                                } else {
                                    stringResource(R.string.home_local_auto_start_requires_background)
                                }
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = autoStart,
                                onCheckedChange = onAutoStartChange,
                                enabled = runInBackground,
                                colors = switchColors,
                            )
                        },
                        modifier = if (runInBackground) {
                            Modifier.clickable { onAutoStartChange(!autoStart) }
                        } else {
                            Modifier
                        },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.home_local_startup_timeout_label)) },
                        supportingContent = { Text(stringResource(R.string.home_local_startup_timeout_value, startupTimeoutSec)) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.a11y_icon_navigate_forward)) },
                        modifier = Modifier.clickable { showTimeoutDialog = true },
                    )
                }
            }
        }
    }

    if (showTimeoutDialog) {
        val timeoutParams = amoledDialogParams(
            normalColor = MaterialTheme.colorScheme.surface,
            shape = ShapeTokens.largeMedium,
        )
        BasicAlertDialog(
            onDismissRequest = { showTimeoutDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = timeoutParams.shape,
                color = timeoutParams.containerColor,
                border = timeoutParams.border,
                tonalElevation = timeoutParams.tonalElevation,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .heightIn(max = 420.dp),
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.home_local_startup_timeout_label),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    AppPickerList(
                        options = timeoutOptions.map { it to stringResource(R.string.home_local_startup_timeout_value, it) },
                        selectedKey = startupTimeoutSec,
                        onSelect = { option ->
                            onStartupTimeoutSecChange(option)
                            showTimeoutDialog = false
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    DialogButtons(
                        buttons = listOf(
                            Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) { showTimeoutDialog = false },
                        )
                    )
                }
            }
        }
    }
}

internal object FullStringMaskTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        return TransformedText(AnnotatedString("\u2022".repeat(raw.length)), OffsetMapping.Identity)
    }
}
