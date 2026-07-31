package dev.leonardo.octether.ui.screens.chat.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.octether.ui.components.AmoledSurface
import dev.leonardo.octether.ui.screens.chat.util.codeHorizontalScroll
import dev.leonardo.octether.ui.screens.chat.util.halfScreenHeight
import dev.leonardo.octether.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.octether.ui.theme.CodeTypography
import dev.leonardo.octether.ui.theme.DiffAdded
import dev.leonardo.octether.ui.theme.DiffRemoved
import dev.leonardo.octether.ui.theme.ShapeTokens
import dev.leonardo.octether.ui.theme.AlphaTokens

/**
 * Inline diff change counts: +N -N with colors.
 */
@Composable
internal fun DiffChangesInline(additions: Int, deletions: Int) {
    val addColor = DiffAdded
    val delColor = DiffRemoved
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (additions > 0) {
            Text(
                text = "+$additions",
                style = CodeTypography.copy(color = addColor)
            )
        }
        if (deletions > 0) {
            Text(
                text = "-$deletions",
                style = CodeTypography.copy(color = delColor)
            )
        }
    }
}

private data class DiffLineStyle(val prefix: String, val text: String, val bgColor: Color, val fgColor: Color)

internal enum class DiffLineType { REMOVED, ADDED, UNCHANGED }
internal data class DiffLine(val type: DiffLineType, val text: String)

/**
 * Unified diff view — shows old lines in red, new lines in green with line numbers and change counts.
 */
@Composable
internal fun DiffView(before: String, after: String) {
    val isAmoled = isAmoledTheme()
    val addColor = DiffAdded
    val delColor = DiffRemoved
    val addBg = DiffAdded.copy(alpha = AlphaTokens.DIFF_BG)
    val delBg = DiffRemoved.copy(alpha = AlphaTokens.DIFF_BG)

    val beforeLines = if (before.isBlank()) emptyList() else before.lines()
    val afterLines = if (after.isBlank()) emptyList() else after.lines()

    val diffLines = remember(before, after) { computeSimpleDiff(beforeLines, afterLines) }

    // Compute stats
    val addedCount = diffLines.count { it.type == DiffLineType.ADDED }
    val removedCount = diffLines.count { it.type == DiffLineType.REMOVED }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Stats row
        if (addedCount > 0 || removedCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (addedCount > 0) {
                    Text(
                        text = "+$addedCount",
                        style = MaterialTheme.typography.labelSmall.copy(color = addColor)
                    )
                }
                if (removedCount > 0) {
                    Text(
                        text = "-$removedCount",
                        style = MaterialTheme.typography.labelSmall.copy(color = delColor)
                    )
                }
            }
        }

        // Diff lines with line numbers
        AmoledSurface(
            isAmoledDark = isAmoled,
            shape = ShapeTokens.extraSmall,
            normalColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = AlphaTokens.FAINT),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = halfScreenHeight())
                .verticalScroll(rememberScrollState())
        ) {
            var lineNumber = 0
            Column(
                modifier = Modifier
                    .codeHorizontalScroll()
                    .padding(4.dp)
            ) {
                diffLines.forEach { diffLine ->
                    val bg = when (diffLine.type) {
                        DiffLineType.ADDED -> addBg
                        DiffLineType.REMOVED -> delBg
                        DiffLineType.UNCHANGED -> Color.Transparent
                    }
                    val fg = when (diffLine.type) {
                        DiffLineType.ADDED -> addColor
                        DiffLineType.REMOVED -> delColor
                        DiffLineType.UNCHANGED -> if (isAmoled) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.HIGH)
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = AlphaTokens.MEDIUM)
                        }
                    }
                    val prefix = when (diffLine.type) {
                        DiffLineType.ADDED -> "+"
                        DiffLineType.REMOVED -> "-"
                        DiffLineType.UNCHANGED -> " "
                    }
                    lineNumber++
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawBehind { drawRect(bg) }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Line number
                        Text(
                            text = "$lineNumber",
                            style = CodeTypography.copy(
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT)
                            ),
                            modifier = Modifier.width(28.dp)
                        )
                        // Prefix + content
                        SelectionContainer {
                            Text(
                                text = "$prefix${diffLine.text}",
                                style = CodeTypography.copy(color = fg)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Simple diff algorithm: find common prefix/suffix lines, show removed and added lines in between.
 * Not a full LCS but good enough for typical edit tool changes.
 */
internal fun computeSimpleDiff(before: List<String>, after: List<String>): List<DiffLine> {
    if (before.isEmpty() && after.isEmpty()) return emptyList()
    if (before.isEmpty()) return after.map { DiffLine(DiffLineType.ADDED, it) }
    if (after.isEmpty()) return before.map { DiffLine(DiffLineType.REMOVED, it) }

    // Find common prefix
    var commonPrefixLen = 0
    while (commonPrefixLen < before.size && commonPrefixLen < after.size &&
        before[commonPrefixLen] == after[commonPrefixLen]) {
        commonPrefixLen++
    }

    // Find common suffix (after prefix)
    var commonSuffixLen = 0
    while (commonSuffixLen < (before.size - commonPrefixLen) &&
        commonSuffixLen < (after.size - commonPrefixLen) &&
        before[before.size - 1 - commonSuffixLen] == after[after.size - 1 - commonSuffixLen]) {
        commonSuffixLen++
    }

    val result = mutableListOf<DiffLine>()

    // Show a few context lines from prefix (max 3)
    val contextLines = 3
    val prefixStart = (commonPrefixLen - contextLines).coerceAtLeast(0)
    for (i in prefixStart until commonPrefixLen) {
        result.add(DiffLine(DiffLineType.UNCHANGED, before[i]))
    }

    // Removed lines (from before, between prefix and suffix)
    for (i in commonPrefixLen until (before.size - commonSuffixLen)) {
        result.add(DiffLine(DiffLineType.REMOVED, before[i]))
    }

    // Added lines (from after, between prefix and suffix)
    for (i in commonPrefixLen until (after.size - commonSuffixLen)) {
        result.add(DiffLine(DiffLineType.ADDED, after[i]))
    }

    // Show a few context lines from suffix (max 3)
    val suffixEnd = commonSuffixLen.coerceAtMost(contextLines)
    for (i in 0 until suffixEnd) {
        result.add(DiffLine(DiffLineType.UNCHANGED, before[before.size - commonSuffixLen + i]))
    }

    return result
}
