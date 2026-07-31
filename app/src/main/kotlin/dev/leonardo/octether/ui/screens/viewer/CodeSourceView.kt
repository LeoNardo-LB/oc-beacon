package dev.leonardo.octether.ui.screens.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.leonardo.octether.domain.model.Annotation
import dev.leonardo.octether.ui.theme.CodeTypography
import dev.leonardo.octether.ui.theme.SpacingTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Pixel threshold from the bottom of scrollable content that triggers [onLoadMore]
 * in annotation (Column) mode. ~50-80 lines depending on font size.
 */
private const val LOAD_MORE_THRESHOLD_PX = 2000

@Composable
fun CodeSourceView(
    content: String,
    filePath: String,
    annotations: List<Annotation> = emptyList(),
    onAnnotate: ((selectedText: String) -> Unit)? = null,
    onTapAnnotation: ((Annotation) -> Unit)? = null,
    modifier: Modifier = Modifier,
    lazyListState: LazyListState = rememberLazyListState(),
    // Phase 4: pagination — null means render all lines (backward compatible)
    visibleLineCount: Int? = null,
    totalLineCount: Int? = null,
    onLoadMore: (() -> Unit)? = null
) {
    if (content.isEmpty()) return

    val isDark = isSystemInDarkTheme()
    val language = HighlightBuilder.rememberLanguage(filePath)
    val highlights = remember(content, language, isDark) {
        HighlightBuilder.buildHighlights(content, language, isDark)
    }
    val annotated = remember(content, highlights) {
        HighlightBuilder.buildAnnotatedStringFromHighlights(content, highlights)
    }
    val lineCount = remember(content) {
        if (content.isEmpty()) 0
        else content.count { it == '\n' } + if (content.endsWith('\n')) 0 else 1
    }
    val lineOffsets = remember(content) {
        buildList {
            add(0)
            content.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
        }.toIntArray()
    }
    val highlightColor = MaterialTheme.colorScheme.primary
    val lineAnnotations = remember(annotations, content, lineCount) {
        val map = mutableMapOf<Int, MutableList<Triple<Int, Int, Int>>>()
        annotations.forEach { ann ->
            val annStartLine = ann.startLine - 1
            val annEndLine = ann.endLine - 1
            for (lineIdx in annStartLine..annEndLine) {
                if (lineIdx < 0 || lineIdx >= lineCount) continue
                val lineStart = lineOffsets[lineIdx]
                val lineEnd = if (lineIdx + 1 < lineOffsets.size) lineOffsets[lineIdx + 1] - 1 else content.length
                val relStart = (ann.startChar - lineStart).coerceAtLeast(0)
                val relEnd = (ann.endChar - lineStart).coerceAtMost(lineEnd - lineStart)
                if (relStart < relEnd) {
                    map.getOrPut(lineIdx) { mutableListOf() }
                      .add(Triple(relStart, relEnd, ann.index + 1))
                }
            }
        }
        map
    }
    val annotationByIndex = remember(annotations) {
        annotations.associateBy { it.index }
    }
    val maxChars = remember(content) {
        var max = 0
        var current = 0
        for (c in content) {
            if (c == '\n') {
                if (current > max) max = current
                current = 0
            } else {
                current++
            }
        }
        if (current > max) max = current
        max
    }
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gutterWidth = remember(lineCount) {
        val digits = maxOf(1, lineCount).toString().length
        (digits * 10 + SpacingTokens.SM).dp
    }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val maxRowWidth = remember(maxChars, gutterWidth, density) {
        val charWidthPx = textMeasurer.measure("M", CodeTypography).size.width
        val maxCodeWidthPx = charWidthPx * maxChars
        with(density) {
            gutterWidth + maxCodeWidthPx.toDp() + SpacingTokens.SM.dp + SpacingTokens.LG.dp
        }
    }
    val hScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val annotationEnabled = onAnnotate != null

    // Phase 4: pagination helpers
    val visLines = visibleLineCount
    val totalLines = totalLineCount
    val hasMore = visLines != null && totalLines != null && visLines < totalLines

    if (annotationEnabled) {
        // ===== Annotation mode: Column + SelectionContainer =====
        // SelectionContainer requires all selectable Text nodes in the SAME composition
        // tree so that character-level selection works and appendTextContextMenuComponents
        // (used by [annotationContextMenu]) can inject the "Annotate" item into the system
        // text selection toolbar. LazyColumn breaks this because each item is composed
        // independently, so we use Column + verticalScroll here.
        //
        // Layout: Row { gutter Column (fixed) | code Column (horizontalScroll) }
        // Both columns live inside the same verticalScroll so gutter and code stay
        // vertically aligned while only the code column scrolls horizontally.
        val verticalScrollState = rememberScrollState()
        val renderLineCount = visLines ?: lineCount

        if (onLoadMore != null && hasMore) {
            LaunchedEffect(verticalScrollState, visLines, totalLines) {
                snapshotFlow {
                    val max = verticalScrollState.maxValue
                    max > 0 && verticalScrollState.value >= max - LOAD_MORE_THRESHOLD_PX
                }
                    .filter { it }
                    .distinctUntilChanged()
                    .collect { onLoadMore() }
            }
        }

        SelectionContainer(modifier = modifier) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScrollState)
                    .padding(vertical = SpacingTokens.SM.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    // Left column: gutter line numbers — fixed width, does NOT scroll horizontally
                    Column(modifier = Modifier.width(gutterWidth)) {
                        for (index in 0 until renderLineCount) {
                            Text(
                                text = "${index + 1}",
                                style = CodeTypography,
                                color = gutterColor,
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    // Right column: code text — scrolls horizontally independently from gutter.
                    // vertical scroll is synced with gutter via the shared enclosing Row.
                    Column(modifier = Modifier.weight(1f).horizontalScroll(hScroll)) {
                        for (index in 0 until renderLineCount) {
                            val start = lineOffsets[index]
                            val endExclusive = if (index + 1 < lineOffsets.size)
                                lineOffsets[index + 1] - 1
                            else
                                content.length
                            val baseLine = annotated.subSequence(start, endExclusive)
                            val lineAnnotated = lineAnnotations[index]?.let { anns ->
                                HighlightBuilder.buildAnnotatedLineWithAnnotations(
                                    baseLine,
                                    anns,
                                    highlightColor
                                )
                            } ?: baseLine
                            Text(
                                text = lineAnnotated,
                                style = CodeTypography,
                                modifier = Modifier
                                    .padding(
                                        start = SpacingTokens.SM.dp,
                                        end = SpacingTokens.LG.dp
                                    )
                                    .annotationContextMenu(onAnnotate)
                            )
                        }
                    }
                }
                // Phase 4: load-more indicator (inside scroll area, appears at bottom)
                if (hasMore) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(SpacingTokens.LG.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).testTag("viewer_load_more_indicator")
                        )
                    }
                }
            }
        }
    } else {
        // ===== Non-annotation mode: LazyColumn (read-only, no SelectionContainer) =====

        // Phase 4: pagination — trigger loadMore when user scrolls near the bottom
        if (onLoadMore != null && hasMore) {
            LaunchedEffect(lazyListState, visLines, totalLines) {
                snapshotFlow {
                    val lastVisible = lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    lastVisible >= visLines - 50
                }
                    .filter { it }
                    .distinctUntilChanged()
                    .collect { onLoadMore() }
            }
        }

        LazyColumn(
            state = lazyListState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = SpacingTokens.SM.dp)
        ) {
            items(
                count = visLines ?: lineCount,
                key = { index -> "line_$index" }  // String key — avoid Int key space collision with Compose internals (crash fix)
            ) { index ->
                val start = lineOffsets[index]
                val endExclusive = if (index + 1 < lineOffsets.size)
                    lineOffsets[index + 1] - 1
                else
                    content.length
                val baseLine = annotated.subSequence(start, endExclusive)
                val lineAnnotated = lineAnnotations[index]?.let { anns ->
                    HighlightBuilder.buildAnnotatedLineWithAnnotations(baseLine, anns, highlightColor)
                } ?: baseLine

                val tapModifier: Modifier = onTapAnnotation?.let { callback ->
                    lineAnnotations[index]?.firstOrNull()?.third?.let { displayIdx ->
                        annotationByIndex[displayIdx - 1]
                    }?.let { ann ->
                        Modifier.clickable { callback(ann) }
                    }
                } ?: Modifier

                Row(
                    modifier = Modifier
                        .defaultMinSize(minWidth = maxRowWidth)
                        .then(tapModifier)
                ) {
                    // Gutter — fixed, does NOT scroll horizontally
                    Text(
                        text = "${index + 1}",
                        style = CodeTypography,
                        color = gutterColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(gutterWidth)
                    )
                    // Code — scrolls horizontally independently from gutter
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(hScroll)
                    ) {
                        Text(
                            text = lineAnnotated,
                            style = CodeTypography,
                            modifier = Modifier.padding(
                                start = SpacingTokens.SM.dp,
                                end = SpacingTokens.LG.dp
                            )
                        )
                    }
                }
            }
            // Phase 4: load-more indicator
            if (hasMore) {
                item(key = "load_more") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(SpacingTokens.LG.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).testTag("viewer_load_more_indicator")
                        )
                    }
                }
            }
        }
    }
}
