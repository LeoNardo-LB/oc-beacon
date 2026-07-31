package dev.leonardo.ocbeacon.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.AutoApproveRule
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * Section in Settings showing saved permission auto-approve rules.
 */
@Composable
internal fun PermissionRulesSection(
    rules: List<AutoApproveRule>,
    onDeleteRule: (AutoApproveRule) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = stringResource(R.string.settings_auto_approve_rules),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.settings_auto_approve_rules),
                style = MaterialTheme.typography.titleSmall
            )
        }

        if (rules.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_no_auto_approve_rules),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp)
            )
        } else {
            rules.forEachIndexed { index, rule ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp))
                }
                RuleRow(
                    rule = rule,
                    onDelete = { onDeleteRule(rule) }
                )
            }
        }
    }
}

@Composable
private fun RuleRow(
    rule: AutoApproveRule,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rule.toolName,
                style = MaterialTheme.typography.bodyMedium
            )
            if (rule.directoryPattern != "*") {
                Text(
                    text = rule.directoryPattern,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.settings_delete_rule),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
