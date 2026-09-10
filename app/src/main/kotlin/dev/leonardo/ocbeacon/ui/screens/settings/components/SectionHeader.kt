package dev.leonardo.ocbeacon.ui.screens.settings.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = SpacingTokens.LG.dp, top = SpacingTokens.LG.dp, bottom = SpacingTokens.XS.dp)
    )
}
