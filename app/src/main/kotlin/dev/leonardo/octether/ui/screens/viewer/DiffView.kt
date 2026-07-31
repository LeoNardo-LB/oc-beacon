package dev.leonardo.octether.ui.screens.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.octether.R
import dev.leonardo.octether.ui.theme.AlphaTokens
import dev.leonardo.octether.ui.theme.CodeTypography
import dev.leonardo.octether.ui.theme.DiffAdded
import dev.leonardo.octether.ui.theme.DiffRemoved
import dev.leonardo.octether.ui.theme.SpacingTokens

/**
 * Renders the unified diff [FileViewerUiState.diff] patch with optional hunk navigation.
 *
 * D4-005: scrolls directly to [DiffHunk.patchStartLineIndex] instead of reverse-looking-up
 * the line by content, which is both slower and fragile against duplicate hunk headers.
 *
 * D3-005: line colors derive from [DiffAdded]/[DiffRemoved] + [AlphaTokens.DIFF_BG]; there are
 * no `DiffAddedBg`/`DiffAddedFg` tokens (they do not exist in the theme).
 */
@Composable
fun DiffView(
    uiState: FileViewerUiState,
    onNextHunk: () -> Unit,
    onPrevHunk: () -> Unit
) {
    val patch = uiState.diff?.patch ?: return
    val lines = remember(patch) { patch.lines() }
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    LaunchedEffect(uiState.currentHunkIndex, uiState.hunks) {
        val target = uiState.hunks.getOrNull(uiState.currentHunkIndex) ?: return@LaunchedEffect
        listState.animateScrollToItem(target.patchStartLineIndex)
    }

    Column(Modifier.fillMaxSize()) {
        if (uiState.hunks.isNotEmpty()) {
            DiffHunkNavigator(
                current = uiState.currentHunkIndex,
                total = uiState.hunks.size,
                onPrevHunk = onPrevHunk,
                onNextHunk = onNextHunk
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            items(lines, key = { it.hashCode() }) { line -> DiffLine(line) }
        }
    }
}

@Composable
private fun DiffLine(line: String) {
    val colorScheme = MaterialTheme.colorScheme
    val (background, foreground) = when {
        line.startsWith("+") -> DiffAdded.copy(alpha = AlphaTokens.DIFF_BG) to DiffAdded
        line.startsWith("-") -> DiffRemoved.copy(alpha = AlphaTokens.DIFF_BG) to DiffRemoved
        line.startsWith("@@") -> colorScheme.primaryContainer to colorScheme.onPrimaryContainer
        else -> Color.Transparent to colorScheme.onSurface
    }
    Text(
        text = line,
        style = CodeTypography,
        color = foreground,
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = SpacingTokens.SM.dp, vertical = 1.dp)
    )
}

@Composable
private fun DiffHunkNavigator(
    current: Int,
    total: Int,
    onPrevHunk: () -> Unit,
    onNextHunk: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SpacingTokens.SM.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPrevHunk,
            enabled = current > 0
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(R.string.a11y_icon_hunk_previous)
            )
        }
        IconButton(
            onClick = onNextHunk,
            enabled = current < total - 1
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.a11y_icon_hunk_next)
            )
        }
        Spacer(Modifier.width(SpacingTokens.SM.dp))
        Text(
            text = "[${current + 1}/$total]",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
