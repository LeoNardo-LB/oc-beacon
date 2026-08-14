package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER as GFMHeader
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW as GFMRow
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL as GFMCell
import dev.leonardo.ocbeacon.ui.theme.LocalChatDensity
import dev.leonardo.ocbeacon.ui.theme.spacing
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/** 表示从 AST 解析出的表格行的数据类。 */
private data class TableRow(
    val isHeader: Boolean,
    val rowIndex: Int,
    val cells: List<ASTNode>,
)

/**
 * #135（D2-L46）：表格测量缓存——探针列宽与行高在"内容、约束、列宽"
 * 未变时复用，避免流式渲染期间每次 measure 对全部单元格重复 3 遍 subcompose。
 * 注意：final 的 [androidx.compose.ui.layout.Placeable] 依赖本次 measure 约束，
 * 不可跨 measure 复用——final 一遍仍须每遍执行。
 */
private class MeasureCache {
    /** 探针测量时的 maxWidth 约束签名（变化 → 列宽缓存失效）。 */
    var probeMaxWidth: Int = -1
    var probeWidths: IntArray? = null
    /** 行高缓存依赖的 finalColWidths 签名（变化 → 行高缓存失效）。 */
    var widthsSignature: IntArray? = null
    var heights: IntArray? = null
}

/**
 * 表格组件——由最宽单元格内容驱动的统一列宽。
 *
 * 使用带 [MeasurePolicy] 的自定义 [Layout] 在单次遍历中测量所有单元格，
 * 计算每列的最大宽度，并将它们放置在统一网格上。
 * 当表格超出父容器宽度时启用水平滚动。
 */
@Composable
internal fun SimpleMarkdownTable(
    content: String,
    tableNode: ASTNode,
    style: TextStyle,
    uriHandler: UriHandler,
    linkColor: Color,
) {
    // 暗色模式下 outlineVariant @ 35% 几乎不可见（与背景融合），改用更亮的
    // outline 并提高不透明度，确保表格轮廓与网格在暗色下清晰可辨。
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val headerBg = MaterialTheme.colorScheme.primaryContainer.copy(
        alpha = if (isDark) AlphaTokens.MEDIUM else AlphaTokens.MUTED
    )
    val rowBgOdd = MaterialTheme.colorScheme.surfaceContainerLow.copy(
        alpha = if (isDark) AlphaTokens.MEDIUM else AlphaTokens.MUTED
    )
    val dividerColor = if (isDark) {
        MaterialTheme.colorScheme.outline.copy(alpha = AlphaTokens.MEDIUM)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
    }
    val pad = LocalChatDensity.current.spacing.tableCell
    val shape = ShapeTokens.smallMedium
    val border = BorderStroke(1.dp, dividerColor)
    val annotator = annotatorSettings()

    val columnCount = remember(tableNode) {
        tableNode.children.maxOfOrNull { child ->
            when (child.type) {
                GFMHeader, GFMRow -> child.children.count { it.type == GFMCell }
                else -> 0
            }
        } ?: 0
    }
    if (columnCount == 0) return

    // 从 AST 收集结构化的行数据
    val rows = remember(tableNode, content) {
        val list = mutableListOf<TableRow>()
        var rowIdx = 0
        tableNode.children.forEach { child ->
            when (child.type) {
                GFMHeader -> {
                    val cells = child.children.filter { it.type == GFMCell }
                    list.add(TableRow(isHeader = true, rowIndex = -1, cells = cells))
                }
                GFMRow -> {
                    val cells = child.children.filter { it.type == GFMCell }
                    list.add(TableRow(isHeader = false, rowIndex = rowIdx, cells = cells))
                    rowIdx++
                }
            }
        }
        list
    }

    val rowCount = rows.size
    val scrollState = rememberScrollState()
    val minCellWidthPx = with(LocalDensity.current) { 120.dp.toPx() }.roundToInt()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(border, shape)
            .clip(shape)
    ) {
        var containerWidth by remember { mutableIntStateOf(0) }

        val headerStyle = style.copy(fontWeight = FontWeight.SemiBold, lineBreak = LineBreak.Simple)
        val bodyStyle = style.copy(lineBreak = LineBreak.Simple)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { containerWidth = it.width }
                .horizontalScroll(scrollState)
        ) {
            val cellContent: @Composable () -> Unit = {
                rows.forEachIndexed { rowIdx, row ->
                    val cellCount = minOf(row.cells.size, columnCount)
                    val isLastRow = rowIdx == rows.lastIndex
                    repeat(cellCount) { colIdx ->
                        val cell = row.cells[colIdx]
                        val isLastCol = colIdx == cellCount - 1
                        val cellStyle = if (row.isHeader) headerStyle else bodyStyle
                        Box(
                            modifier = Modifier
                                .background(
                                    when {
                                        row.isHeader -> headerBg
                                        row.rowIndex % 2 == 1 -> rowBgOdd
                                        else -> Color.Transparent
                                    }
                                )
                                .then(
                                    // 完整网格：列分隔线（除最后一列）+ 行分隔线（除最后一行），
                                    // 与外层 border 组成闭合边框，与 WebView 表格渲染保持一致。
                                    Modifier.drawBehind {
                                        if (!isLastCol) {
                                            drawLine(
                                                dividerColor,
                                                Offset(size.width, 0f),
                                                Offset(size.width, size.height),
                                                strokeWidth = 1f
                                            )
                                        }
                                        if (!isLastRow) {
                                            drawLine(
                                                dividerColor,
                                                Offset(0f, size.height),
                                                Offset(size.width, size.height),
                                                strokeWidth = 1f
                                            )
                                        }
                                    }
                                )
                                .padding(horizontal = pad, vertical = if (row.isHeader) 8.dp else 6.dp)
                        ) {
                            // AnnotatedString 内嵌 style 颜色，键必须含颜色：主题切换后
                            // 颜色变化 → 重建 AnnotatedString，避免文字停留旧主题颜色。
                            val cellResult = remember(content, cell, cellStyle.color, linkColor) {
                                buildClickableMarkdown(content, cell, cellStyle, annotator, linkColor)
                            }
                            var cellLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                            MarkdownBasicText(
                                text = cellResult.annotatedString,
                                style = cellStyle,
                                onTextLayout = { cellLayoutResult = it },
                                modifier = Modifier.clickableMarkdown(cellResult, { cellLayoutResult }, uriHandler),
                            )
                        }
                    }
                }
            }

            // #135（D2-L46）：测量缓存——原实现每次 measure 对全部单元格执行
            // 3 遍 subcompose（probe/final-pass1/final）。流式渲染时内容变化频繁
            // 但布局参数（内容、容器宽度、约束）未变，探针列宽与行高可复用，
            // 重复测量降为 1 遍 subcompose（final 的 placeable 不可跨 measure 复用，
            // 仍须每遍执行）。
            // 缓存 key 用 content + tableNode（AST 引用，内容变化时必然变化）——
            // bodyStyle 每次重组都是新 TextStyle 对象，不能作 key（否则缓存每次失效）
            val measureCache = remember(content, tableNode, columnCount) {
                MeasureCache()
            }

            SubcomposeLayout { constraints ->
                if (rows.isEmpty()) return@SubcomposeLayout layout(0, 0) {}

                val looseConstraints = Constraints(
                    minWidth = 0,
                    maxWidth = constraints.maxWidth,
                    minHeight = 0,
                    maxHeight = constraints.maxHeight,
                )
                // 探针列宽：约束与内容未变时复用缓存（跳过整遍 subcompose）
                val colWidths: IntArray
                if (measureCache.probeMaxWidth == constraints.maxWidth && measureCache.probeWidths != null) {
                    colWidths = measureCache.probeWidths!!
                } else {
                    val probePlaceables = subcompose("probe", cellContent).map { it.measure(looseConstraints) }
                    colWidths = IntArray(columnCount) { 0 }
                    probePlaceables.forEachIndexed { index, placeable ->
                        val col = index % columnCount
                        colWidths[col] = maxOf(colWidths[col], placeable.width)
                    }
                    measureCache.probeMaxWidth = constraints.maxWidth
                    measureCache.probeWidths = colWidths
                }

                // 动态列宽上限：cap = max(容器宽 / 列数, MIN_CELL)
                val effectiveCap = if (containerWidth > 0) {
                    maxOf(containerWidth / columnCount, minCellWidthPx)
                } else {
                    minCellWidthPx
                }
                val cappedWidths = IntArray(columnCount) { col ->
                    minOf(colWidths[col], effectiveCap)
                }

                // 填满策略：使用 containerWidth 而非 constraints.maxWidth
                val naturalWidth = cappedWidths.sum()
                val parentWidth = containerWidth
                val finalColWidths = if (naturalWidth > 0 && parentWidth > 0 && naturalWidth < parentWidth) {
                    val scale = parentWidth.toFloat() / naturalWidth.toFloat()
                    val scaled = IntArray(columnCount) { col ->
                        (cappedWidths[col] * scale).toInt()
                    }
                    val diff = parentWidth - scaled.sum()
                    for (i in 0 until diff.coerceAtMost(columnCount)) {
                        scaled[i] += 1
                    }
                    scaled
                } else {
                    cappedWidths
                }

                val finalMeasurables = subcompose("final", cellContent)
                val actualRowCount = rows.size

                // 行高：finalColWidths 未变时复用缓存（内容/列宽不变 → 行高不变）
                val rowHeights: IntArray
                if (measureCache.heights != null && measureCache.widthsSignature?.contentEquals(finalColWidths) == true) {
                    rowHeights = measureCache.heights!!
                } else {
                    val pass1Measurables = subcompose("final-pass1", cellContent)
                    val naturalPlaceables = pass1Measurables.mapIndexed { index, measurable ->
                        val col = index % columnCount
                        measurable.measure(
                            Constraints(
                                minWidth = finalColWidths[col],
                                maxWidth = finalColWidths[col],
                                minHeight = 0,
                                maxHeight = constraints.maxHeight,
                            )
                        )
                    }
                    rowHeights = IntArray(actualRowCount) { 0 }
                    naturalPlaceables.forEachIndexed { index, placeable ->
                        val row = index / columnCount
                        rowHeights[row] = maxOf(rowHeights[row], placeable.height)
                    }
                    measureCache.widthsSignature = finalColWidths.copyOf()
                    measureCache.heights = rowHeights
                }

                // 第二遍：以行高作为 minHeight 重新测量，使每个单元格 Box
                // 高度拉伸到整行高度——单元格背景因此填满整个单元格矩形，
                // 而不是只覆盖文字区域。
                val finalPlaceables = finalMeasurables.mapIndexed { index, measurable ->
                    val col = index % columnCount
                    val row = index / columnCount
                    measurable.measure(
                        Constraints(
                            minWidth = finalColWidths[col],
                            maxWidth = finalColWidths[col],
                            minHeight = rowHeights[row],
                            maxHeight = constraints.maxHeight,
                        )
                    )
                }

                val totalWidth = finalColWidths.sum()
                val totalHeight = rowHeights.sum()

                layout(totalWidth, totalHeight) {
                    var y = 0
                    for (row in 0 until actualRowCount) {
                        var x = 0
                        for (col in 0 until columnCount) {
                            val idx = row * columnCount + col
                            if (idx < finalPlaceables.size) {
                                finalPlaceables[idx].placeRelative(x, y)
                            }
                            x += finalColWidths[col]
                        }
                        y += rowHeights[row]
                    }
                }
            }
        }
    }
}
