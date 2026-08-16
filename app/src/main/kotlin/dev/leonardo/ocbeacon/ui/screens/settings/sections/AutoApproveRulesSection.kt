package dev.leonardo.ocbeacon.ui.screens.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.screens.settings.SettingsViewModel
import dev.leonardo.ocbeacon.ui.screens.settings.components.PermissionRulesSection
import dev.leonardo.ocbeacon.ui.screens.settings.components.SectionHeader
import dev.leonardo.ocbeacon.ui.theme.ListItemTokens

@Composable
fun AutoApproveRulesSection(viewModel: SettingsViewModel) {
    val autoApproveRules by viewModel.autoApproveRules.collectAsStateWithLifecycle()
    // 2026-08-16（用户需求）：自动允许所有权限请求开关——开启后任何
    // PermissionAsked 到达即自动应答 always（服务器落持久规则，同类请求
    // 不再询问）。与下方规则列表同区：列表正是 always 应答累积的结果。
    val autoAllowPermissions by viewModel.autoAllowPermissions.collectAsStateWithLifecycle()

    SectionHeader(stringResource(R.string.settings_auto_approve_rules))
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_auto_allow_permissions)) },
        supportingContent = { Text(stringResource(R.string.settings_auto_allow_permissions_desc)) },
        leadingContent = {
            Icon(
                Icons.Default.VerifiedUser,
                contentDescription = stringResource(R.string.a11y_settings_auto_allow_permissions)
            )
        },
        trailingContent = {
            Switch(
                checked = autoAllowPermissions,
                onCheckedChange = { viewModel.setAutoAllowPermissions(it) },
            )
        },
        modifier = Modifier.clickable { viewModel.setAutoAllowPermissions(!autoAllowPermissions) }
            .padding(ListItemTokens.ContentPaddingMedium)
    )
    PermissionRulesSection(
        rules = autoApproveRules,
        onDeleteRule = { rule -> viewModel.deletePermissionRule(rule) }
    )
}
