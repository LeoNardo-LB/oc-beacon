package dev.leonardo.ocbeacon.ui.screens.server.providers.dsh

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.DshCustomProviderDraft
import dev.leonardo.ocbeacon.domain.model.DshCustomProviders
import dev.leonardo.ocbeacon.domain.model.DshDiscoveredModel
import dev.leonardo.ocbeacon.domain.model.DshProviderDirectoryEntry
import dev.leonardo.ocbeacon.ui.components.DialogButtonRole
import dev.leonardo.ocbeacon.ui.components.DialogButtons
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.components.amoledOutlinedTextFieldColors
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ButtonTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import kotlinx.coroutines.launch

/**
 * #324① DSH provider 目录区块（ServerProvidersScreen 内，isDsh 门控）。
 *
 * 形态按 #313 自有行范式：SectionHeader + ListItem 行（目录合流行——显示名/
 * route/运行时态/凭据态），自定义行（llm-pi-ai settingsPath）带删除；底部
 * 「新增自定义 provider」入口开表单对话框（route/显示名/baseURL/协议/密钥 +
 * discoverModels 探查勾选）。凭据字段走 credentials/set——**不回显明文**
 * （credentials/describe 只回 configured/writable）。
 *
 * ⚠️ 本区块宿主是 ServerProvidersScreen 的外层 LazyColumn——内部**禁止**再
 * 引入纵向滚动容器（LazyColumn/verticalScroll），否则无限高度约束测量期崩溃
 * （#324① F1 事故；回归测试 DshCustomProvidersSectionLayoutTest）。
 */
@Composable
internal fun DshCustomProvidersSection(
    directory: List<DshProviderDirectoryEntry>,
    loading: Boolean,
    settingsBlocked: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onDiscover: suspend (baseURL: String, apiKey: String) -> Result<List<DshDiscoveredModel>>,
    onCreate: (DshCustomProviderDraft, (Boolean, String?) -> Unit) -> Unit,
    onDelete: (route: String, (Boolean, String?) -> Unit) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DshProviderDirectoryEntry?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.dsh_providers_section_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                // W4/D2(2026-09-06 全量 E2E):Add 图标曾误接 onRefresh(tap 只触发
                // 目录刷新),showCreate 全历史无 =true 赋值——整套新增表单对话框
                // 为不可达死代码。正接:Add=开表单;刷新独立成钮(文案复用
                // workspace_refresh)。
                IconButton(onClick = { showCreate = true }) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.dsh_provider_add_custom),
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.workspace_refresh),
                    )
                }
            }
        }
        if (settingsBlocked) {
            Text(
                text = stringResource(R.string.dsh_settings_loopback_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (directory.isEmpty() && !loading) {
            Text(
                text = stringResource(R.string.dsh_providers_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    // F1 修复（#324①）：本区块嵌在 ServerProvidersScreen 的外层 LazyColumn
    // item 内——纵向滚动容器（LazyColumn）在其中被以无限最大高度约束测量，
    // 测量期即抛 IllegalStateException（提供方页 100% 崩溃）。目录条目量小
    // （内置目录 + 自定义，数十级），按 ServerSettingsContent 既有约定改
    // Column + forEach（该文件 143 行注释明令禁嵌套 LazyColumn）。
    Column(modifier = Modifier.fillMaxWidth()) {
        directory.forEach { entry ->
            val isCustom = entry.isCustom
            ListItem(
                headlineContent = {
                    Text(if (entry.row.displayName.isBlank()) entry.row.provider else entry.row.displayName)
                },
                supportingContent = {
                    Column {
                        Text(
                            text = entry.row.provider,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM),
                        )
                        Text(
                            text = if (entry.row.active) {
                                stringResource(R.string.dsh_provider_active)
                            } else {
                                stringResource(R.string.dsh_provider_inactive)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                        )
                    }
                },
                leadingContent = {
                    val configured = entry.credential?.configured == true
                    if (entry.credential != null) {
                        Icon(
                            if (configured) Icons.Filled.Key else Icons.Filled.KeyOff,
                            contentDescription = stringResource(
                                if (configured) R.string.dsh_provider_credential_configured
                                else R.string.dsh_provider_credential_missing
                            ),
                            tint = if (configured) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                        )
                    }
                },
                trailingContent = if (isCustom && !settingsBlocked) {
                    {
                        IconButton(onClick = { pendingDelete = entry }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.dsh_provider_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                } else null,
                modifier = Modifier.clickable(enabled = false) { },
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
            )
        }
    }

    if (showCreate) {
        DshCustomProviderCreateDialog(
            takenRoutes = directory.map { it.row.provider }.toSet(),
            settingsBlocked = settingsBlocked,
            onDiscover = onDiscover,
            onDismiss = { showCreate = false },
            onCreate = { draft, onDone ->
                onCreate(draft) { ok, err ->
                    if (ok) showCreate = false
                    onDone(ok, err)
                }
            },
        )
    }

    pendingDelete?.let { entry ->
        DshDeleteProviderConfirmDialog(
            entry = entry,
            onConfirm = {
                val target = entry
                pendingDelete = null
                onDelete(target.row.provider) { _, _ -> }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** 新建自定义 provider 对话框（表单 + discoverModels 探查 + 模型勾选）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DshCustomProviderCreateDialog(
    takenRoutes: Set<String>,
    settingsBlocked: Boolean,
    onDiscover: suspend (baseURL: String, apiKey: String) -> Result<List<DshDiscoveredModel>>,
    onDismiss: () -> Unit,
    onCreate: (DshCustomProviderDraft, (Boolean, String?) -> Unit) -> Unit,
) {
    var route by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var baseURL by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(DshCustomProviders.PROTOCOLS.first()) }
    var apiKey by remember { mutableStateOf("") }
    var discovered by remember { mutableStateOf<List<DshDiscoveredModel>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var discovering by remember { mutableStateOf(false) }
    var discoverError by remember { mutableStateOf<String?>(null) }
    var createError by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val routeInvalid = route.isNotEmpty() && !DshCustomProviders.isValidRoute(route)
    val routeTaken = route in takenRoutes
    val canCreate = route.isNotBlank() && !routeInvalid && !routeTaken &&
        baseURL.isNotBlank() && selected.isNotEmpty() && !creating && !settingsBlocked

    val dialogParams = amoledDialogParams(
        normalColor = MaterialTheme.colorScheme.surface,
        shape = ShapeTokens.largeMedium,
    )
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = dialogParams.shape,
            color = dialogParams.containerColor,
            border = dialogParams.border,
            tonalElevation = dialogParams.tonalElevation,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.dsh_provider_add_custom),
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedTextField(
                    value = route,
                    onValueChange = { route = it },
                    label = { Text(stringResource(R.string.dsh_provider_route)) },
                    isError = routeInvalid || routeTaken,
                    supportingText = {
                        when {
                            routeInvalid -> Text(stringResource(R.string.dsh_provider_route_invalid))
                            routeTaken -> Text(stringResource(R.string.dsh_provider_route_taken))
                            else -> Text(stringResource(R.string.dsh_provider_route_hint))
                        }
                    },
                    singleLine = true,
                    colors = amoledOutlinedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.dsh_provider_display_name)) },
                    singleLine = true,
                    colors = amoledOutlinedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = baseURL,
                    onValueChange = { baseURL = it },
                    label = { Text(stringResource(R.string.dsh_provider_base_url)) },
                    singleLine = true,
                    colors = amoledOutlinedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                // 协议选择（三固定项；服务端 schema 序）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DshCustomProviders.PROTOCOLS.forEach { choice ->
                        OutlinedButton(
                            onClick = { protocol = choice },
                            enabled = !settingsBlocked,
                            colors = if (protocol == choice) ButtonTokens.filledColors()
                            else ButtonDefaults.outlinedButtonColors(),
                            border = ButtonTokens.amoledBorder(),
                        ) {
                            Text(choice, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.dsh_provider_api_key)) },
                    supportingText = { Text(stringResource(R.string.dsh_provider_api_key_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = amoledOutlinedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            discovering = true
                            discoverError = null
                            scope.launch {
                                onDiscover(baseURL, apiKey).fold(
                                    onSuccess = { discovered = it },
                                    onFailure = { discoverError = it.message },
                                )
                                discovering = false
                            }
                        },
                        enabled = !discovering,
                    ) {
                        Text(stringResource(R.string.dsh_provider_discover))
                    }
                    if (discovering) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
                discoverError?.let {
                    Text(
                        text = stringResource(R.string.dsh_provider_discover_failed, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (discovered.isEmpty()) {
                    Text(
                        text = stringResource(R.string.dsh_provider_models_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.dsh_provider_model_select),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    discovered.forEach { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (model.id in selected) selected - model.id
                                    else selected + model.id
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = model.id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + model.id else selected - model.id
                                },
                            )
                            Column {
                                Text(model.name ?: model.id, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = model.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                                )
                            }
                        }
                    }
                }
                createError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                DialogButtons(
                    buttons = buildList {
                        add(Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) { onDismiss() })
                        add(Triple(
                            stringResource(R.string.dsh_provider_create),
                            DialogButtonRole.Primary,
                        ) {
                            creating = true
                            createError = null
                            onCreate(
                                DshCustomProviderDraft(
                                    route = route.trim(),
                                    displayName = displayName.trim(),
                                    baseURL = baseURL.trim(),
                                    api = protocol,
                                    apiKey = apiKey.trim(),
                                    models = discovered.filter { it.id in selected },
                                ),
                            ) { ok, err ->
                                creating = false
                                if (!ok) createError = err
                            }
                        })
                    },
                )
            }
        }
    }
}

/** 删除确认对话框（对齐既有删除确认范式）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DshDeleteProviderConfirmDialog(
    entry: DshProviderDirectoryEntry,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dialogParams = amoledDialogParams(
        normalColor = MaterialTheme.colorScheme.surface,
        shape = ShapeTokens.largeMedium,
    )
    val label = if (entry.row.displayName.isBlank()) entry.row.provider else entry.row.displayName
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = dialogParams.shape,
            color = dialogParams.containerColor,
            border = dialogParams.border,
            tonalElevation = dialogParams.tonalElevation,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.dsh_provider_delete_confirm_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.dsh_provider_delete_confirm_text, label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) { onDismiss() },
                        Triple(stringResource(R.string.dsh_provider_delete), DialogButtonRole.Danger) {
                            onConfirm()
                            onDismiss()
                        },
                    ),
                )
            }
        }
    }
}
