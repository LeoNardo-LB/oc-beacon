package dev.leonardo.ocbeacon.ui.screens.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.ServerType
import dev.leonardo.ocbeacon.ui.components.DialogButtonRole
import dev.leonardo.ocbeacon.ui.components.DialogButtons
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens

/** #391 切片8：服务器类型标签（用户选择面；新增类型时补一条本地化映射）。 */
private fun serverTypeLabel(type: ServerType): Int = when (type) {
    ServerType.OpenCode -> R.string.server_type_opencode
    ServerType.Dsh -> R.string.server_type_dsh
}

/**
 * 解析并校验服务器 URL 字符串。
 * 接受如下格式：
 *   http://192.168.0.10:4199
 *   https://192.168.0.10
 *   https://my-server.example.com:4848
 *   192.168.0.10:4199           -> 默认使用 http://
 *   192.168.0.10                -> 默认使用 http://
 *
 * 返回规范化后的 URL（带 scheme），无效则返回 null。
 */
private fun validateAndNormalizeUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return null

    // 缺失 scheme 时补上
    val withScheme = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        "http://$trimmed"
    } else {
        trimmed
    }

    return try {
        val url = java.net.URL(withScheme)
        // 必须有 host
        if (url.host.isNullOrBlank()) return null
        // 指定了端口时必须合法
        if (url.port != -1 && url.port !in 1..65535) return null
        // 重建干净的 URL（scheme + host + 可选端口）
        val port = url.port
        if (port != -1) {
            "${url.protocol}://${url.host}:$port"
        } else {
            "${url.protocol}://${url.host}"
        }
    } catch (e: Exception) {
        null
    }
}

private fun deriveServerNameFromUrl(normalizedUrl: String): String {
    return try {
        val url = java.net.URL(normalizedUrl)
        val host = url.host
        val port = url.port
        if (port != -1) "$host:$port" else host
    } catch (_: Exception) {
        normalizedUrl
            .removePrefix("http://")
            .removePrefix("https://")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerDialog(
    server: ServerConfig?,
    // #325②：配对深链预填（DSH 地址；非 null 时新建对话框默认选中 DSH 类型）
    prefillUrl: String? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, username: String, password: String, autoConnect: Boolean, serverType: ServerType) -> Unit,
    /**
     * #391 切片8：可选服务器类型由适配器注册表枚举驱动（默认仅显示已注册类型；
     * 调用方传 viewModel.supportedServerTypes）。为空时回落到默认类型，保证可编辑。
     */
    serverTypes: List<ServerType> = listOf(ServerType.OpenCode),
) {
    // #115（D2-L25）：服务器名输入 saveable
    var name by rememberSaveable { mutableStateOf(server?.name ?: "") }
    // #325②：深链预填优先于 "http://" 占位（编辑态沿用条目现值）
    var url by remember { mutableStateOf(server?.url ?: prefillUrl ?: "http://") }
    var username by remember { mutableStateOf(server?.username ?: "opencode") }
    var password by remember { mutableStateOf(server?.password ?: "") }
    var autoConnect by remember { mutableStateOf(server?.autoConnect ?: false) }
    // #276：服务器类型（enum 是 Serializable，rememberSaveable 原生支持）；
    // #325②：配对深链预填 → 默认 DSH
    var serverType by rememberSaveable {
        mutableStateOf(server?.serverType ?: if (prefillUrl != null) ServerType.Dsh else ServerType.OpenCode)
    }
    val isDsh = serverType == ServerType.Dsh

    var urlError by remember { mutableStateOf<String?>(null) }

    val urlRequiredText = stringResource(R.string.server_url)
    val urlInvalidText = stringResource(R.string.server_invalid_url)
    val dialogMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f
    val scrollState = rememberScrollState()

    val params = amoledDialogParams(
        normalColor = MaterialTheme.colorScheme.surface,
        shape = ShapeTokens.largeMedium,
    )
    val switchColors = SwitchDefaults.colors()

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = params.shape,
            color = params.containerColor,
            border = params.border,
            tonalElevation = params.tonalElevation,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = dialogMaxHeight)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (server != null) stringResource(R.string.home_edit) else stringResource(R.string.server_add),
                        style = MaterialTheme.typography.headlineSmall
                    )

                    // #276：服务器类型选择（M3 SegmentedButton 单选；DSH 无鉴权——
                    // 选中后隐藏用户名/密码并切换 URL 提示）
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            serverTypes.forEachIndexed { index, type ->
                                SegmentedButton(
                                    selected = serverType == type,
                                    onClick = { serverType = type },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = serverTypes.size)
                                ) {
                                    Text(stringResource(serverTypeLabel(type)))
                                }
                            }
                        }
                        if (isDsh) {
                            Text(
                                text = stringResource(R.string.server_type_dsh_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.server_name)) },
                        placeholder = { Text(stringResource(R.string.server_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    val currentUrlError = urlError
                    OutlinedTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            urlError = null
                        },
                        label = { Text(stringResource(R.string.server_url)) },
                        placeholder = { Text(stringResource(R.string.server_url_hint)) },
                        isError = urlError != null,
                        supportingText = if (currentUrlError != null) {
                            { Text(currentUrlError) }
                        } else {
                            {
                                Text(
                                    stringResource(
                                        // #276：DSH 提示走 adb reverse 127.0.0.1:3080 用法
                                        if (isDsh) R.string.server_url_example_dsh
                                        else R.string.server_url_example
                                    )
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // #325：DSH 首次配对辅助区（三通道指引——粘贴手动[现状]/
                    // adb 注入[dev]/深链填表；轻量自有形态，无新增依赖）
                    if (isDsh) {
                        Surface(
                            shape = ShapeTokens.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.FAINT),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.server_pair_help_title),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = stringResource(R.string.server_pair_help_manual),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(R.string.server_pair_help_adb),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(R.string.server_pair_help_deeplink),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // #276：DSH 无鉴权（§2.1）——用户名/密码字段隐藏
                    if (!isDsh) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text(stringResource(R.string.server_username)) },
                            placeholder = { Text(stringResource(R.string.server_username_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.server_password)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Surface(
                        shape = ShapeTokens.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AlphaTokens.FAINT),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.server_auto_connect),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = stringResource(R.string.server_auto_connect_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(
                                checked = autoConnect,
                                onCheckedChange = { autoConnect = it },
                                colors = switchColors
                            )
                        }
                    }
                }

                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.server_cancel), DialogButtonRole.Secondary, onDismiss),
                        Triple(stringResource(R.string.server_save), DialogButtonRole.Primary) {
                            val normalizedUrl = validateAndNormalizeUrl(url)
                            urlError = when {
                                url.isBlank() -> urlRequiredText
                                normalizedUrl == null -> urlInvalidText
                                else -> null
                            }

                            if (urlError == null && normalizedUrl != null) {
                                val finalName = name.trim().ifBlank {
                                    deriveServerNameFromUrl(normalizedUrl)
                                }
                                onSave(
                                    finalName,
                                    normalizedUrl,
                                    // DSH 无鉴权：字段已隐藏，恒定占位值（DSH 传输层忽略 auth）
                                    if (isDsh) "opencode" else username.ifBlank { "opencode" },
                                    if (isDsh) "" else password,
                                    autoConnect,
                                    serverType
                                )
                            }
                        },
                    )
                )
            }
        }
    }
}
