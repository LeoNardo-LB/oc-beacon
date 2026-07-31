package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import kotlinx.coroutines.delay

/**
 * Error state shown when loading fails and there are no messages to display.
 * Auto-retries after a 5-second countdown.
 */
@Composable
fun ChatErrorState(
    modifier: Modifier = Modifier,
    error: String?,
    onRetry: () -> Unit
) {
    var countdown by remember(error) { mutableIntStateOf(5) }

    LaunchedEffect(error) {
        countdown = 5
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
        onRetry()
    }

    Column(
        modifier = modifier
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = stringResource(R.string.a11y_icon_warning),
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error
        )
        ErrorPayloadContent(
            text = error ?: stringResource(R.string.session_unknown_error),
            textStyle = MaterialTheme.typography.bodyLarge,
            textColor = MaterialTheme.colorScheme.error,
        )
        Button(onClick = onRetry) {
            Text(stringResource(R.string.retry) + if (countdown > 0) " ($countdown)" else "")
        }
    }
}
