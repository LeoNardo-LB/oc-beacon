package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.leonardo.ocbeacon.ui.screens.chat.util.JumpTarget
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dialog listing all user questions for quick navigation.
 *
 * @param show whether the dialog is visible
 * @param jumpTargets extracted user questions (see JumpTargetExtractor)
 * @param currentRawIndex rawIndex of the currently-visible question, for highlight; null = none
 * @param onJump invoked with msgId when user taps a question
 * @param onDismiss invoked when dialog should close
 */
@Composable
fun QuickNavigateSheet(
    show: Boolean,
    jumpTargets: List<JumpTarget>,
    currentRawIndex: Int?,
    onJump: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return

    val listState = rememberLazyListState()

    // Auto-scroll to the currently-highlighted question when the dialog opens,
    // so the user sees their current position instead of Q1.
    LaunchedEffect(show, currentRawIndex) {
        if (currentRawIndex != null) {
            val targetIndex = jumpTargets.indexOfFirst { it.rawIndex == currentRawIndex }
            if (targetIndex >= 0) {
                listState.scrollToItem(targetIndex)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f),
            shape = ShapeTokens.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.SM.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "快速定位",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }

                if (jumpTargets.isEmpty()) {
                    Text(
                        text = "暂无提问",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = SpacingTokens.XXL.dp),
                        textAlign = TextAlign.Center,
                    )
                    return@Column
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = SpacingTokens.XXL.dp),
                ) {
                    items(jumpTargets, key = { it.msgId }) { target ->
                        JumpTargetRow(
                            target = target,
                            isCurrent = target.rawIndex == currentRawIndex,
                            onClick = { onJump(target.msgId) },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JumpTargetRow(
    target: JumpTarget,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val timeText = remember(target.timestampMs) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(target.timestampMs))
    }
    val highlightBg = if (isCurrent) {
        MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.SELECTED)
    } else {
        Color.Transparent
    }
    val accent = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isCurrent) Modifier.drawBehind {
                    val w = 4.dp.toPx()
                    drawRect(
                        color = accent,
                        topLeft = Offset(0f, 0f),
                        size = Size(w, size.height),
                    )
                } else Modifier
            )
            .background(highlightBg)
            .clickable(onClick = onClick)
            .padding(
                start = if (isCurrent) SpacingTokens.MD.dp else SpacingTokens.LG.dp,
                end = SpacingTokens.LG.dp,
                top = SpacingTokens.MD.dp,
                bottom = SpacingTokens.MD.dp,
            ),
    ) {
        // Row 1: Q label + timestamp
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = target.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isCurrent) accent
                    else MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.HIGH),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(SpacingTokens.SM.dp))
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
            )
        }
        Spacer(Modifier.height(SpacingTokens.XS.dp))
        // Row 2: preview, horizontally scrollable (not truncated)
        Text(
            text = target.preview,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.HIGH),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}
