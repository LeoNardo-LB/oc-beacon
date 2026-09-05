package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.DshPluginEnabled
import dev.leonardo.ocbeacon.domain.model.DshPluginInventory
import dev.leonardo.ocbeacon.domain.model.DshSettingsField
import dev.leonardo.ocbeacon.domain.model.DshSettingsFieldKind
import dev.leonardo.ocbeacon.domain.model.DshSettingsNamespaceForm
import dev.leonardo.ocbeacon.domain.model.DshSettingsOp
import dev.leonardo.ocbeacon.ui.components.amoledOutlinedTextFieldColors
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ButtonTokens
import kotlinx.serialization.json.JsonPrimitive

/**
 * #324③ 服务器插件清单区块（ServerSettingsContent 内，DSH 门控）。
 *
 * pluginInventory/list 只读展示（web mod19 同为只读——无 mutate 面）：全局
 * entries 行（模块名/启停/运行态）+ per-preset 分组行（enabled 三态 + 条件说明）。
 */
@Composable
fun DshPluginInventorySection(
    inventory: DshPluginInventory?,
) {
    if (inventory == null) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = stringResource(R.string.dsh_plugins_inventory_title),
            expanded = expanded,
            onClick = { expanded = !expanded },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT))
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (inventory.entries.isEmpty() && inventory.presets.isEmpty()) {
                    Text(
                        text = stringResource(R.string.dsh_plugins_inventory_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                inventory.entries.forEach { entry ->
                    SettingsListRow(
                        leading = { PhaseDot(phase = entry.fiberPhase) },
                        title = entry.moduleName,
                        subtitle = if (entry.enabled) {
                            stringResource(R.string.dsh_plugin_enabled)
                        } else {
                            stringResource(R.string.dsh_plugin_disabled)
                        },
                        onClick = null,
                    )
                }
                inventory.presets.forEach { preset ->
                    Text(
                        text = stringResource(R.string.dsh_plugin_preset_group, preset.name),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                    preset.rows.forEach { row ->
                        val stateLabel = when (row.enabled) {
                            DshPluginEnabled.ENABLED -> stringResource(R.string.dsh_plugin_enabled)
                            DshPluginEnabled.DISABLED -> stringResource(R.string.dsh_plugin_disabled)
                            DshPluginEnabled.CONDITIONAL -> stringResource(R.string.dsh_plugin_conditional)
                        }
                        SettingsListRow(
                            leading = { PhaseDot(phase = row.fiberPhase) },
                            title = row.moduleName,
                            subtitle = row.condition ?: stateLabel,
                            onClick = null,
                        )
                    }
                }
            }
        }
    }
}

/** 运行态指示（fiberPhase null = 无 fiber 静默点）。 */
@Composable
private fun PhaseDot(phase: String?) {
    val color = when (phase) {
        "active" -> MaterialTheme.colorScheme.primary
        "failed" -> MaterialTheme.colorScheme.error
        "pending", "loading", "unloading" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    androidx.compose.foundation.Canvas(modifier = Modifier.size(10.dp)) {
        drawCircle(color = color)
    }
}

/**
 * #324③ 服务器配置动态表单区块（settings/describe schema 驱动轻量表单）。
 *
 * 每 ns 一张卡：ns 名 + 生效方式（live/restart）标注 + 顶层标量字段控件
 * （TEXT/NUMBER/BOOLEAN/ENUM-chip/SECRET 只写）+ 单字段保存（settings/mutate
 * 乐观并发）。secret 走 credentials/set（ref = 同 ns *Env 字段值；不可解析时
 * 字段禁写）。403 → loopback 标注（DefaultsBlockedSection 先例）。
 */
@Composable
fun DshServerConfigSection(
    forms: List<DshSettingsNamespaceForm>,
    blocked: Boolean,
    onSaveField: (ns: String, revision: Long, op: DshSettingsOp) -> Unit,
    onSaveSecret: (ref: String, value: String) -> Unit,
) {
    if (forms.isEmpty() && !blocked) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSectionHeader(
            title = stringResource(R.string.dsh_server_config_title),
            expanded = expanded,
            onClick = { expanded = !expanded },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT))
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (blocked) {
                    Text(
                        text = stringResource(R.string.dsh_settings_loopback_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                forms.forEach { form ->
                    DshNamespaceFormCard(
                        form = form,
                        writable = form.writable && !blocked,
                        onSaveField = onSaveField,
                        onSaveSecret = onSaveSecret,
                    )
                }
            }
        }
    }
}

@Composable
private fun DshNamespaceFormCard(
    form: DshSettingsNamespaceForm,
    writable: Boolean,
    onSaveField: (ns: String, revision: Long, op: DshSettingsOp) -> Unit,
    onSaveSecret: (ref: String, value: String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = form.ns,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (form.applies == "restart") {
                    stringResource(R.string.dsh_config_applies_restart)
                } else {
                    stringResource(R.string.dsh_config_applies_live)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
            )
        }
        form.fields.forEach { field ->
            DshFormField(
                ns = form.ns,
                revision = form.revision,
                field = field,
                writable = writable,
                onSaveField = onSaveField,
                onSaveSecret = onSaveSecret,
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun DshFormField(
    ns: String,
    revision: Long,
    field: DshSettingsField,
    writable: Boolean,
    onSaveField: (ns: String, revision: Long, op: DshSettingsOp) -> Unit,
    onSaveSecret: (ref: String, value: String) -> Unit,
) {
    when (field.kind) {
        DshSettingsFieldKind.BOOLEAN -> {
            val current = (field.value as? JsonPrimitive)?.content == "true"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = field.key,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    enabled = writable,
                    checked = current,
                    onCheckedChange = { checked ->
                        onSaveField(ns, revision, DshSettingsOp.Set(field.key, JsonPrimitive(checked)))
                    },
                )
            }
        }
        DshSettingsFieldKind.ENUM -> {
            val current = (field.value as? JsonPrimitive)?.content
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    text = field.key,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    field.options.forEach { option ->
                        OutlinedButton(
                            enabled = writable && option != current,
                            onClick = {
                                onSaveField(ns, revision, DshSettingsOp.Set(field.key, JsonPrimitive(option)))
                            },
                            colors = if (option == current) ButtonTokens.filledColors()
                            else androidx.compose.material3.ButtonDefaults.outlinedButtonColors(),
                        ) {
                            Text(option, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        DshSettingsFieldKind.SECRET -> {
            var draft by rememberSaveable(field.key) { mutableStateOf("") }
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = field.key,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (field.secretSet == true) {
                            stringResource(R.string.dsh_provider_credential_configured)
                        } else {
                            stringResource(R.string.dsh_provider_credential_missing)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                    )
                }
                val ref = field.secretRef
                if (ref != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            label = { Text(stringResource(R.string.dsh_provider_api_key)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = writable,
                            colors = amoledOutlinedTextFieldColors(),
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            enabled = writable && draft.isNotBlank(),
                            onClick = {
                                onSaveSecret(ref, draft.trim())
                                draft = ""
                            },
                            colors = ButtonTokens.filledColors(),
                            border = ButtonTokens.amoledBorder(),
                        ) {
                            Text(stringResource(R.string.server_save))
                        }
                    }
                } else if (field.secretSet == true) {
                    Text(
                        text = stringResource(R.string.dsh_secret_no_ref),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                    )
                }
            }
        }
        else -> {
            // TEXT / NUMBER：草稿 + 单字段保存（不自动写——server-side revision 并发敏感）
            var draft by rememberSaveable(field.key, field.value.toString()) {
                mutableStateOf((field.value as? JsonPrimitive)?.content ?: "")
            }
            var saving by rememberSaveable { mutableStateOf(false) }
            val isNumber = field.kind == DshSettingsFieldKind.NUMBER
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(field.key) },
                        singleLine = true,
                        enabled = writable,
                        keyboardOptions = if (isNumber) {
                            KeyboardOptions(keyboardType = KeyboardType.Number)
                        } else {
                            KeyboardOptions.Default
                        },
                        colors = amoledOutlinedTextFieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Button(
                            enabled = writable,
                            onClick = {
                                val value = if (isNumber) {
                                    draft.trim().toLongOrNull()?.let { JsonPrimitive(it) }
                                        ?: draft.trim().toDoubleOrNull()?.let { JsonPrimitive(it) }
                                } else {
                                    JsonPrimitive(draft)
                                }
                                if (value != null) {
                                    saving = true
                                    onSaveField(ns, revision, DshSettingsOp.Set(field.key, value))
                                    saving = false
                                }
                            },
                            colors = ButtonTokens.filledColors(),
                            border = ButtonTokens.amoledBorder(),
                        ) {
                            Text(stringResource(R.string.server_save))
                        }
                    }
                }
            }
        }
    }
}
