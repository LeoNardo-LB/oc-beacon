package dev.leonardo.octether.ui.screens.workspace.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.octether.domain.model.VcsChange
import dev.leonardo.octether.domain.model.VcsStatus
import dev.leonardo.octether.ui.theme.DiffAdded
import dev.leonardo.octether.ui.theme.DiffRemoved
import dev.leonardo.octether.ui.theme.ShapeTokens
import dev.leonardo.octether.ui.theme.SpacingTokens

/**
 * Single row in the Git changes list. Renders a colored status badge
 * (A=green / D=red / M=tertiary), the file path, and `+additions -deletions` stats.
 *
 * Tap invokes [onClick] (typically opening the diff view for [VcsChange.file]).
 */
@Composable
fun GitChangeItem(
    change: VcsChange,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = SpacingTokens.MD.dp,
                vertical = SpacingTokens.SM.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val badgeColor = when (change.status) {
            VcsStatus.ADDED -> DiffAdded
            VcsStatus.DELETED -> DiffRemoved
            VcsStatus.MODIFIED -> MaterialTheme.colorScheme.tertiary
        }
        Surface(
            color = badgeColor,
            contentColor = MaterialTheme.colorScheme.onTertiary,
            shape = ShapeTokens.extraSmall,
            modifier = Modifier.size(20.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = change.status.name.first().toString(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.width(SpacingTokens.MD.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = change.file,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "+${change.additions} -${change.deletions}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
