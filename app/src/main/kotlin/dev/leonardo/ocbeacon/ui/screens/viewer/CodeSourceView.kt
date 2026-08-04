package dev.leonardo.ocbeacon.ui.screens.viewer

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
import dev.leonardo.ocbeacon.domain.model.Annotation
import dev.leonardo.ocbeacon.ui.theme.CodeTypography
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * 在批注（Column）模式下触发 [onLoadMore] 的、距可滚动内容底部的像素阈值。
 * 依字体大小约相当于 50-80 行。
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
    // Phase 4：分页 — null 表示渲染所有行（向后兼容）
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

    // Phase 4：分页辅助函数
    val visLines = visibleLineCount
    val totalLines = totalLineCount
    val hasMore = visLines != null && totalLines != null && visLines < totalLines

    if (annotationEnabled) {
        // ===== 批注模式：Column + SelectionContainer =====
        // SelectionContainer 要求所有可选中的 Text 节点位于同一棵组合树中，
        // 这样字符级选择才能工作，appendTextContextMenuComponents
        //（被 [annotationContextMenu] 使用）才能把"批注"项注入系统文本
        // 选择工具栏。LazyColumn 会破坏这一点，因为每个 item 独立组合，
        // 所以这里改用 Column + verticalScroll。
        //
        // 布局：Row { gutter Column（固定） | code Column（horizontalScroll） }
        // 两列都在同一个 verticalScroll 内，因此 gutter 与代码保持垂直对齐，
        // 而只有代码列水平滚动。
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
                    // 左列：gutter 行号 — 固定宽度，不水平滚动
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
                    // 右列：代码文本 — 独立于 gutter 水平滚动。
                    // 垂直滚动通过共享的外层 Row 与 gutter 同步。
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
                // Phase 4：加载更多指示器（位于滚动区内部，出现在底部）
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
        // ===== 非批注模式：LazyColumn（只读，无 SelectionContainer） =====

        // Phase 4：分页 — 用户滚动到接近底部时触发 loadMore
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
                key = { index -> "line_$index" }  // 字符串 key — 避免 Int key 空间与 Compose 内部冲突（修复崩溃）
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
                    // Gutter — 固定，不水平滚动
                    Text(
                        text = "${index + 1}",
                        style = CodeTypography,
                        color = gutterColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(gutterWidth)
                    )
                    // 代码 — 独立于 gutter 水平滚动
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
            // Phase 4：加载更多指示器
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
